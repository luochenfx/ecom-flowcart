package io.autocommerce.worker.flow;

import io.autocommerce.core.catalog.model.Listing;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 编排链 activity 契约（specs/0007 §4.4）：编排壳自身零 I/O，两处副作用落在 activity 侧。
 *
 * <p>两个 activity 都不含业务逻辑——装配委托 {@code ListingDraftFactory}，就绪判定只是回读已落库
 * 文档的 {@code degraded_steps}；它们只做「读 store → 改 / 判 → 落库（如需要）」的闭合。
 *
 * <p><b>为什么子链不在这里启动</b>：内容链 / 铺货链是 <b>child workflow</b>（{@code Workflow.newChildWorkflowStub}），
 * 由 {@link ListingFlowWorkflowImpl} 启动——**不得**在 activity 内调 {@code ContentWorkflowLauncher} /
 * {@code PublishWorkflowLauncher}。后者是 Temporal 官方明列的反模式：activity 阻塞、重试时重复启动子链、
 * 子链不随父链取消传播（由 {@code ListingFlowRuntimeArchitectureTest} 机械拦截）。
 */
@ActivityInterface
public interface ListingFlowActivities {

    /**
     * 装配待就绪 Listing 并落库（specs/0007 §4.4 步 1）：{@code store.get(spuId)} →
     * {@code ListingDraftFactory.draft} → {@code store.put(新文档)}；返回装配后的 Listing
     * （内容链读回它作为输入）。
     */
    @ActivityMethod
    Listing assembleListing(AssembleListingInput input);

    /**
     * 断言内容就绪（specs/0007 §5）：回读落库文档里的目标 Listing，{@code degraded_steps} 非空即抛
     * <b>不可重试</b>失败（不启动铺货链——降级的 Listing 不许铺货）。返回就绪 Listing 供铺货链使用。
     *
     * @param spuId     CatalogStore 文档键
     * @param listingId 目标 Listing（内容链产物所在）
     */
    @ActivityMethod
    Listing assertContentReady(String spuId, String listingId);
}
