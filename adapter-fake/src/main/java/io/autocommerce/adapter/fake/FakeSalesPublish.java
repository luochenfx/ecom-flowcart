package io.autocommerce.adapter.fake;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.dto.PlatformItemRef;
import io.autocommerce.core.contract.dto.PublishResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 测试 Adapter 的铺货能力实现（specs/0007 §9.1）——{@link PublishCapability} 的可控实现。
 *
 * <p><b>不是 mock</b>：它实现真实能力接口、只抛 {@link AdapterException}，走真实的
 * {@code PublishService} 状态机，仅把"外部平台"这一个边界换成可控结局。调用留痕
 * （{@link #addCalls()} / {@link #reconcileCalls()}）供测试断言"到底有没有真的重发 / 重复 add"。
 *
 * <p><b>四态处置映射</b>（specs/0007 §9.1，务必按此，不自创）：
 * <ul>
 *   <li>{@link Disposition#PUBLISHED} → {@code add} 正常返回
 *       {@link PublishResult}(listingId, platformItemId, url)；</li>
 *   <li>{@link Disposition#AMBIGUOUS} → {@code add} 抛
 *       {@link AdapterException#ambiguous}（<b>必需</b>：挂起-裁定路径的唯一覆盖来源）；</li>
 *   <li>{@link Disposition#REJECTED} → {@code add} 抛
 *       {@link AdapterException#nonRetryable}；</li>
 *   <li>{@link Disposition#RETRYABLE} → {@code add} 抛
 *       {@link AdapterException#retryable}（可带 Retry-After 窗口）。</li>
 * </ul>
 *
 * <p>脚本化：{@link #script} 按调用次序消费结局，用尽后回落到 {@link #addFallback}
 * （默认 {@code PUBLISHED}）——形态对齐 publish 模块的
 * {@code FixturePublishPlatformAdapter.script(...)}。
 */
public final class FakeSalesPublish implements PublishCapability {

    /** 单次 {@code add} 的结局脚本（四态处置，specs/0007 §9.1）。 */
    public enum Disposition {
        /** 成功铺货：返回平台商品引用。 */
        PUBLISHED,
        /** 超时歧义（AMBIGUOUS）：不知是否生效，不得自动重发，走挂起-裁定。 */
        AMBIGUOUS,
        /** 业务拒绝（NON_RETRYABLE）：目标平台永久拒绝，不重试。 */
        REJECTED,
        /** 临时故障（RETRYABLE）：平台限流 / 抖动，可退避重试。 */
        RETRYABLE
    }

    /** AMBIGUOUS 结局的默认平台错误码。 */
    public static final String AMBIGUOUS_CODE = "FAKE_TIMEOUT";
    /** REJECTED 结局的默认平台错误码。 */
    public static final String REJECTED_CODE = "FAKE_CATEGORY_INVALID";
    /** RETRYABLE 结局的默认平台错误码。 */
    public static final String RETRYABLE_CODE = "429";

    private final List<Disposition> script = new ArrayList<>();
    private Disposition fallback = Disposition.PUBLISHED;
    private String platformItemId = "fake-item-1";
    private String platformItemUrl = "https://fake-sales.example.com/item/fake-item-1";
    private Duration retryableAfter;

    private final List<Listing> addCalls = new ArrayList<>();
    private final List<String> reconcileCalls = new ArrayList<>();

    /** 追加 add 结局脚本（按调用次序消费；用尽后回落到 {@link #addFallback}）。 */
    public FakeSalesPublish script(Disposition... dispositions) {
        script.addAll(List.of(dispositions));
        return this;
    }

    /** 脚本用尽后的 add 结局（默认 {@link Disposition#PUBLISHED}）。 */
    public FakeSalesPublish addFallback(Disposition disposition) {
        this.fallback = disposition;
        return this;
    }

    /** 配置 PUBLISHED 结局返回的平台商品引用。 */
    public FakeSalesPublish platformItem(String id, String url) {
        this.platformItemId = id;
        this.platformItemUrl = url;
        return this;
    }

    /** 配置 RETRYABLE 结局携带的 Retry-After / 节流窗口（可空 = 不携带）。 */
    public FakeSalesPublish retryableAfter(Duration duration) {
        this.retryableAfter = duration;
        return this;
    }

    @Override
    public PublishResult add(Listing listing) throws AdapterException {
        addCalls.add(listing);
        Disposition disposition = script.isEmpty() ? fallback : script.remove(0);
        return switch (disposition) {
            case PUBLISHED -> new PublishResult(listing.listingId(), platformItemId, platformItemUrl);
            case AMBIGUOUS -> throw AdapterException.ambiguous(AMBIGUOUS_CODE, "add 请求超时，不知是否生效");
            case REJECTED -> throw AdapterException.nonRetryable(REJECTED_CODE, "目标平台拒绝铺货");
            case RETRYABLE -> throw retryableException();
        };
    }

    private AdapterException retryableException() {
        return retryableAfter == null
                ? AdapterException.retryable(RETRYABLE_CODE, "平台限流")
                : AdapterException.retryable(RETRYABLE_CODE, "平台限流", retryableAfter);
    }

    @Override
    public Optional<PlatformItemRef> reconcile(String listingId) throws AdapterException {
        reconcileCalls.add(listingId);
        return Optional.empty();
    }

    /** add 调用留痕（按调用次序）。 */
    public List<Listing> addCalls() {
        return List.copyOf(addCalls);
    }

    /** reconcile 调用留痕（按调用次序）。 */
    public List<String> reconcileCalls() {
        return List.copyOf(reconcileCalls);
    }
}
