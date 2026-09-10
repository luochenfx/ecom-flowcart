package io.autocommerce.worker.content;

import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.content.ContentChainFailedException;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.ContentStepExecutor;
import io.autocommerce.content.ContentStepRun;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.core.catalog.model.DegradedStep;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.worker.event.EventPublisher;
import io.autocommerce.worker.event.NoopEventPublisher;
import io.autocommerce.worker.event.SysWorkflowFailedEvent;
import io.autocommerce.worker.event.WorkflowFailedReason;

import java.util.List;
import java.util.Objects;

/**
 * 内容链 activity 实现：**单步 + 落库**（每步一次调用）。
 *
 * <p>为什么"每步都 read → 改 → write"而不是把文档揣在 workflow 里传递：
 * <ul>
 *   <li>payload 小：workflow 载荷只走坐标与执行记录，product 文档不进 history（文档会随媒体增长）；</li>
 *   <li>可观测：每步结束产物即刻落库，人工在铺货前就能看到中间稿，无需等整条链收敛；</li>
 *   <li>可续跑：activity 天然可重试，重跑同一步 = 覆盖同字段（Step 幂等，specs/0006 §5）。</li>
 * </ul>
 *
 * <p>失败语义不在此处判断——{@link ContentStepExecutor} 是唯一判定点（硬依赖失败抛
 * {@code ContentChainFailedException}，非硬依赖失败收敛为 {@code DEGRADED} 记录）。
 *
 * <p><b>AC-6 硬失败告警</b>（specs/0006 §5）：本类在硬依赖失败抛回 Temporal 之前，先通过
 * {@link EventPublisher#publishFailed(SysWorkflowFailedEvent)} 发一个 {@code sys.workflow.failed}
 * 信号事件给看板订阅；事件 payload 仅引用 Temporal workflow 坐标 + 失败 Step + 原因，**不依赖业务库
 * 行**（避免双写一致性问题）。A-prime 降级语义：process 在落库后发事件前 crash → 事件丢失；订阅方
 * 需配合 Temporal UI 巡检兜底（Temporal event history 才是真相源）。事务基建（落库与发事件同事务）
 * 属 #22 publish 跨域基建，#20 不引入。
 */
public final class ContentChainActivitiesImpl implements ContentChainActivities {

    private final CatalogStore store;
    private final ContentStepExecutor executor;
    private final EventPublisher events;
    private final String workflowType;
    private final WorkflowCoordinates coordinates;

    /**
     * @param events        事件发布器；生产换 RabbitMQ 实现，测试传 {@link NoopEventPublisher}
     * @param workflowType  workflow 类型（如 {@code ContentWorkflow}），写进事件 payload
     * @param coordinates   Temporal 坐标提供器（workflowId / runId / 业务键），由 worker 装配
     *                      时注入 Temporal ActivityExecutionContext 派生值
     */
    public ContentChainActivitiesImpl(CatalogStore store, ContentStepExecutor executor, EventPublisher events,
                                      String workflowType, WorkflowCoordinates coordinates) {
        this.store = Objects.requireNonNull(store, "CatalogStore 必填");
        this.executor = Objects.requireNonNull(executor, "ContentStepExecutor 必填");
        this.events = Objects.requireNonNull(events, "EventPublisher 必填");
        this.workflowType = Objects.requireNonNull(workflowType, "workflowType 必填");
        this.coordinates = Objects.requireNonNull(coordinates, "WorkflowCoordinates 必填");
    }

    /** Temporal worker 装配时的便捷入口：用 {@link NoopEventPublisher} 兜底。 */
    public ContentChainActivitiesImpl(CatalogStore store, ContentStepExecutor executor, String workflowType,
                                      WorkflowCoordinates coordinates) {
        this(store, executor, new NoopEventPublisher(), workflowType, coordinates);
    }

    @Override
    public ContentStepRun runStep(ContentStepInput input) {
        ContentWorkingSet working = ContentWorkingSet.of(load(input.listing()), input.listing().listingId());
        ContentStepRun run;
        try {
            run = executor.execute(working,
                    new ContentPlan.PlanStep(input.stepId(), input.critical()));
        } catch (ContentChainFailedException e) {
            // AC-6：硬依赖失败 → 发 sys.workflow.failed 信号（事件丢失由看板 + Temporal UI 兜底，见类 javadoc）。
            // 落库先于发事件：失败的 listing 此时已部分落库（前面非硬依赖 Step 的 degraded_steps 可能已写）。
            events.publishFailed(new SysWorkflowFailedEvent(
                    workflowType, coordinates.workflowId(), coordinates.runId(),
                    input.listing().spuId(), input.listing().listingId(),
                    e.stepId(), WorkflowFailedReason.NON_RETRYABLE, e.getMessage()));
            throw e;
        }
        store.put(executor.materialize(working));
        return run;
    }

    @Override
    public List<DegradedStep> degradedSteps(ListingRef listing) {
        return ContentWorkingSet.of(load(listing), listing.listingId()).degradedSteps();
    }

    private ProductCatalog load(ListingRef listing) {
        return store.get(listing.spuId()).orElseThrow(() -> new IllegalStateException(
                "CatalogStore 无此 SPU 文档: " + listing.spuId()
                        + "（内容链的前置 = #19 采集已落库，或 Listing 装配已写回）"));
    }
}
