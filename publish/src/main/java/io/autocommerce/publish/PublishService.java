package io.autocommerce.publish;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.dto.PlatformItemRef;
import io.autocommerce.core.contract.dto.PublishResult;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * 铺货业务服务（publish 模块，**纯 Java**：零 Temporal / 零平台 SDK，禁环②）。
 *
 * <p>职责 = 状态机决策 + 落库（{@link PublishStateStore}）+ 调用 {@link PublishCapability}；
 * 把所有副作用（外部调用、时钟、落库）集中在此，使 Temporal 编排壳（worker-runtime）只需按
 * {@link PublishDisposition} 做控制流。
 *
 * <h2>两条 reconcile 触发路径（specs/0001 §4/§5，开放点定稿）</h2>
 * <ol>
 *   <li><b>(a) 可重入 / 重放的"先查后发"</b>：{@link #publish} 入口先做 store 可重入检查（已有
 *       {@code platform_item_id} → 幂等命中，不 add），再调 {@link PublishCapability#reconcile}
 *       核实平台侧是否已存在（支持者回填、不重复 add）。此路径的 reconcile 属 <b>best-effort 优化</b>：
 *       平台侧读失败（任意 {@code AdapterException}）<b>不阻断</b>首次发布，落空继续 add——
 *       因为此刻我们尚未 add，不存在"重发"风险。</li>
 *   <li><b>(b) add 抛 AMBIGUOUS 后的核实</b>：{@code add} 抛 {@code AMBIGUOUS}（超时/断连不知是否生效）
 *       时，<b>先</b>调 reconcile 核实——有平台结果则回填 PUBLISHED；<b>无果 / 未实现 reconcile 才保守
 *       挂起</b>（{@code AMBIGUOUS}，specs/0001 §5「未实现或查询无果时走保守路径，不自动重发」）。
 *       挂起后由人工/对账 signal 决定下一步（见 worker-runtime 的 {@code PublishWorkflow}）：
 *       「确认未生效 → 重试 add」会再次进入本 {@link #publish}（即又走一次 reconcile-first 的安全网），
 *       「确认回填」走 {@link #confirmPublished}，「业务拒绝」走 {@link #reject}。</li>
 * </ol>
 *
 * <h2>幂等与重放安全</h2>
 * 所有落库辅助方法（{@code record*}）在同一事实重放时复用<b>已落库事实时间</b>（不重新取
 * {@link Clock}）——这是 {@code envelope.id} 幂等锚稳定的前提（activity 重跑 / broker 重投产同一 id）。
 *
 * <p><b>残余窗口（如实登记，不假装闭合）</b>：activity 级保证是 at-least-once。{@link #publish} 入口
 * 的可重入检查能兜住"已落库 PUBLISHED 事实、但结果未回传"的重跑（不重复 add）。但
 * <b>AMBIGUOUS 事实落库后、activity 返回前</b>若 crash，重跑会再次 reconcile-first + add——而此刻平台侧
 * 是否已生效本就未知，故这一次重发仍可能造成重复铺货。彻底闭合需 activity 级幂等键或平台侧查重端点，
 * 二者当前均不存在（ADR-0003 已定：幂等由 execution 级唯一性承担，不建登记表）。v1 以"确定性
 * workflowId + 可重入检查"为界，此窗口如实登记。
 *
 * <p><b>add 成功、落库失败窗口（单独登记）</b>：{@code add} 已成功返回（平台侧商品已创建）但随后的
 * PUBLISHED 落库抛异常（非 {@code AdapterException}）时，<b>刻意不</b>按"bug → UNEXPECTED → FAILED"
 * 归类——FAILED 会经 {@code AllowDuplicateFailedOnly} 授权同 id 新 run，新 run 的 {@code inspect}
 * 查不到 {@code platform_item_id} → reconcile 无果 → 再次 add，即<b>真的二次铺货</b>，与 ADR-0003
 * 「AMBIGUOUS 绝不自动重发」冲突。故该情形改归 {@link PublishDisposition#AMBIGUOUS} 挂起（非终态、
 * 不授权重铺），见 {@link #recordAddOutcome} / {@link #suspendUnrecordedEffect}。
 *
 * <p><b>安全网的成立边界（如实登记，不夸口）</b>：{@link #recordAddOutcome} 之后"重入仍先查 +
 * reconcile-first"只在<b>单次落库失败、store 仍可读</b>时成立（重入时可重入短路或 reconcile 命中，
 * 不重复 add）。<b>持久写失败（读可用，如磁盘满 / 只读挂载）下该安全网不成立</b>——无 PUBLISHED 行可查、
 * reconcile 常无果，重入会再次 add。因此本修复<b>不让该路径进入任何可重试 / 可重放分支</b>：连
 * AMBIGUOUS 事实也写不下时归 {@link PublishDisposition#SUSPENDED_UNRECORDED}（挂起、不写终态、
 * 不发事件），使 {@code add} 调用次数<b>恒为 1</b>；store 恢复后由 operator signal（{@code confirmPublished}）
 * 回填 PUBLISHED。绝不退回 {@code RETRYABLE}（activity 退避重试会重跑 add）。
 */
public final class PublishService {

    private final PublishStateStore store;
    private final PublishCapability capability;
    private final Clock clock;

    public PublishService(PublishStateStore store, PublishCapability capability, Clock clock) {
        this.store = Objects.requireNonNull(store, "PublishStateStore 必填");
        this.capability = Objects.requireNonNull(capability, "PublishCapability 必填");
        this.clock = Objects.requireNonNull(clock, "Clock 必填");
    }

    /**
     * 可重入检查（specs/0001 §4：新 run 开头先查 Listing 状态）：已有 {@code platform_item_id} 的
     * PUBLISHED 事实 → 幂等命中（{@link PublishDisposition#ALREADY_PUBLISHED}），否则需继续 add。
     *
     * <p>只读 store、<b>不</b>触外部调用——workflow 首个 activity 用它做廉价短路。
     */
    public PublishDecision inspect(Listing listing) {
        requireListing(listing);
        return store.get(listing.listingId())
                .filter(state -> state.status() == PublishStatus.PUBLISHED)
                .map(PublishDecision::alreadyPublished)
                .orElseGet(() -> PublishDecision.needsAdd(listing.listingId()));
    }

    /**
     * reconcile-first + add（见类 javadoc 的路径 (a)）。返回最终处置；<b>不抛</b> {@code AdapterException}
     * ——把所有平台异常归类为 {@link PublishDisposition}，由编排壳决定 Temporal 原语（AC-4）。
     *
     * <p>入口同样先做可重入检查：这是 activity 级 at-least-once 的兜底——若上一次尝试已落库
     * PUBLISHED 事实但结果未回传、Temporal 重跑本 activity，此处直接命中而不重复 add。
     */
    public PublishDecision publish(Listing listing) {
        requireListing(listing);
        String listingId = listing.listingId();

        Optional<PublishState> existing = store.get(listingId);
        if (existing.isPresent() && existing.get().status() == PublishStatus.PUBLISHED) {
            return PublishDecision.alreadyPublished(existing.get());
        }

        Optional<PlatformItemRef> reconciled;
        try {
            reconciled = capability.reconcile(listingId);
        } catch (AdapterException e) {
            reconciled = Optional.empty(); // best-effort：读失败不阻断首次发布
        } catch (RuntimeException e) {
            return classify(listingId, e); // 非 AdapterException = bug（specs/0005 §6）
        }
        if (reconciled.isPresent()) {
            PlatformItemRef ref = reconciled.get();
            return PublishDecision.published(recordPublished(listingId, ref.platformItemId(), ref.url()));
        }

        PublishResult result;
        try {
            result = capability.add(listing);
        } catch (RuntimeException e) {
            return classify(listingId, e); // add 未返回：常规失败归类（RETRYABLE / REJECTED / AMBIGUOUS / UNEXPECTED）
        }
        // add 已成功返回 → 外部副作用已生效；此后的落库失败绝不得归 FAILED（会授权重铺，见类 javadoc）
        return recordAddOutcome(listingId, result);
    }

    /**
     * 人工/对账确认"已生效"（AMBIGUOUS 挂起的一条出边）：落库 PUBLISHED + 回填平台引用。
     * 幂等：同一 listingId 已有 PUBLISHED 事实时复用其 {@code publishedAt}。
     */
    public PublishDecision confirmPublished(String listingId, String platformItemId, String platformItemUrl) {
        requireListingId(listingId);
        if (platformItemId == null || platformItemId.isBlank()) {
            throw new IllegalArgumentException("platformItemId 必填（确认回填须带平台商品 id）");
        }
        return PublishDecision.published(recordPublished(listingId, platformItemId, platformItemUrl));
    }

    /** 人工确认"业务拒绝"（AMBIGUOUS 出边）：落库 REJECTED（workflow 随后以 failed 收尾）。 */
    public PublishDecision reject(String listingId, String reason) {
        requireListingId(listingId);
        return PublishDecision.rejected(recordTerminal(listingId, PublishStatus.REJECTED, reason));
    }

    /**
     * 收口 FAILED（RETRYABLE 重试耗尽 / 意外未知）：落库 FAILED + 结构化原因。
     * 由编排壳在 activity 重试耗尽后调用（AC-4）。
     */
    public PublishDecision fail(String listingId, String reason) {
        requireListingId(listingId);
        return PublishDecision.failed(recordTerminal(listingId, PublishStatus.FAILED, reason));
    }

    // ---------------- 内部：失败归类 ----------------

    private PublishDecision classify(String listingId, RuntimeException throwable) {
        PublishFailure failure = PublishFailure.classify(throwable);
        return switch (failure.kind()) {
            case AMBIGUOUS -> {
                // 挂起前先 reconcile 核实（可选能力）：有平台结果则回填，无果才保守挂起（specs/0001 §5）
                Optional<PlatformItemRef> ref = reconcileQuietly(listingId);
                if (ref.isPresent()) {
                    yield PublishDecision.published(
                            recordPublished(listingId, ref.get().platformItemId(), ref.get().url()));
                }
                yield PublishDecision.ambiguous(recordAmbiguous(listingId, failure.reason()));
            }
            case REJECTED ->
                    PublishDecision.rejected(recordTerminal(listingId, PublishStatus.REJECTED, failure.reason()));
            case UNEXPECTED ->
                    PublishDecision.failed(recordTerminal(listingId, PublishStatus.FAILED, failure.reason()));
            case RETRYABLE -> PublishDecision.retryable(listingId, failure.reason());
        };
    }

    private Optional<PlatformItemRef> reconcileQuietly(String listingId) {
        try {
            return capability.reconcile(listingId);
        } catch (RuntimeException e) {
            return Optional.empty(); // 核实失败 = 无果 → 保守挂起
        }
    }

    // ---------------- 内部：add 已生效后的落库（绝不误判 FAILED） ----------------

    /**
     * {@code add} 已成功返回后的落库与收口（AC-3 语义边界 / ADR-0003）。
     *
     * <p>落库成功 → {@link PublishDisposition#PUBLISHED}；落库失败（非 {@code AdapterException}）→
     * <b>不</b>走 {@link #classify}（那会归 UNEXPECTED → FAILED，从而授权同 id 重铺、二次铺货），
     * 而是归"外部已生效 / 本地未知"的歧义挂起，见 {@link #suspendUnrecordedEffect}。
     */
    private PublishDecision recordAddOutcome(String listingId, PublishResult result) {
        try {
            return PublishDecision.published(
                    recordPublished(listingId, result.platformItemId(), result.url()));
        } catch (RuntimeException recordFailure) {
            return suspendUnrecordedEffect(listingId, recordFailure);
        }
    }

    /**
     * "add 已生效、PUBLISHED 落库失败" → {@link PublishDisposition#AMBIGUOUS} 挂起（绝不判 FAILED）。
     *
     * <p>先尽力把 AMBIGUOUS 事实落库（挂起态需被 {@code inspect} / 看板看到，且使 workflow 停在
     * 非终态、不授权重铺）；若连 AMBIGUOUS 也落不下（状态库整体不可用），退回
     * {@link PublishDisposition#SUSPENDED_UNRECORDED}——workflow 同样挂起等 signal，但<b>不写任何终态、
     * 不发领域事件</b>（事实未落库，不得作总线 first write）。
     *
     * <p><b>为什么不退回 {@link PublishDisposition#RETRYABLE}</b>：可重试错误会让 Activity 退避重试
     * （{@code doNotRetry} 不含 RETRYABLE），重试即重跑 {@link #publish}；此刻无 PUBLISHED 行、可重入
     * 短路失效 → 会<b>再次 {@code add}</b>（持久写失败下最多重复 5 次），违反 ADR-0003 / AC-3。挂起处置
     * 使 {@code add} 调用次数<b>恒为 1</b>。
     */
    private PublishDecision suspendUnrecordedEffect(String listingId, RuntimeException recordFailure) {
        String reason = "add 已生效但 PUBLISHED 落库失败（" + recordFailure.getClass().getName()
                + "）：外部已创建平台商品、本地无记录 → 归歧义挂起，绝不自动重发（ADR-0003 / specs/0001 §5）";
        try {
            return PublishDecision.ambiguous(recordAmbiguous(listingId, reason));
        } catch (RuntimeException storeUnavailable) {
            // 状态库整体不可用：连 AMBIGUOUS 事实也写不下。
            // 绝不 RETRYABLE（activity 退避重试 → 重跑 add → 重复铺货）；绝不 FAILED（授权重铺 → 同样重发）；
            // 也不返回 AMBIGUOUS（会 emit listing.ambiguous，但本路径事实未落库 → 违反 AC-5）。
            return PublishDecision.suspendedUnrecorded(listingId, reason
                    + "；状态库暂不可用（" + storeUnavailable.getClass().getName() + "），已挂起等 signal");
        }
    }

    // ---------------- 内部：落库（重放安全，复用已落库事实时间） ----------------

    private PublishState recordPublished(String listingId, String platformItemId, String platformItemUrl) {
        Optional<PublishState> existing = store.get(listingId);
        if (existing.isPresent() && existing.get().status() == PublishStatus.PUBLISHED
                && Objects.equals(existing.get().platformItemId(), platformItemId)) {
            return existing.get(); // 同一已落库事实重放 → 复用 publishedAt（保 envelope.id 稳定）
        }
        String now = clock.instant().toString();
        return store.put(new PublishState(listingId, PublishStatus.PUBLISHED, platformItemId,
                platformItemUrl, now, null, now));
    }

    private PublishState recordAmbiguous(String listingId, String reason) {
        Optional<PublishState> existing = store.get(listingId);
        if (existing.isPresent() && existing.get().status() == PublishStatus.AMBIGUOUS) {
            return existing.get(); // 重放复用
        }
        String now = clock.instant().toString();
        return store.put(new PublishState(listingId, PublishStatus.AMBIGUOUS, null, null, null, reason, now));
    }

    private PublishState recordTerminal(String listingId, PublishStatus status, String reason) {
        Optional<PublishState> existing = store.get(listingId);
        if (existing.isPresent() && existing.get().status() == status
                && Objects.equals(existing.get().reason(), reason)) {
            return existing.get(); // 同一终态事实重放 → 复用 updatedAt
        }
        String now = clock.instant().toString();
        return store.put(new PublishState(listingId, status, null, null, null, reason, now));
    }

    private static void requireListing(Listing listing) {
        Objects.requireNonNull(listing, "listing 必填");
        requireListingId(listing.listingId());
    }

    private static void requireListingId(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("listingId 必填");
        }
    }
}
