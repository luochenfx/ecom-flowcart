package io.autocommerce.api;

import io.autocommerce.catalog.ingest.CatalogIngestService;
import io.autocommerce.catalog.ingest.CatalogIngestService.IngestResult;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.worker.flow.ListingFlowRuntime;
import io.autocommerce.worker.flow.ListingFlowWorkflowLauncher;
import io.autocommerce.worker.publish.PublishRuntime;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 对外 REST 入口（specs/0007 §6.1，api 模块）：
 *
 * <ul>
 *   <li>{@code POST /api/v1/ingest} —— <b>同步</b>采集（{@link CatalogIngestService}）→ <b>异步</b>
 *       启动编排链（{@link ListingFlowWorkflowLauncher#start}）→ 返回 {@code 202} + 链路坐标。
 *       采集失败（{@code AdapterException}）经 {@link ApiExceptionHandler} 映射为 {@code 503} / {@code 422}，
 *       且<b>绝不启动编排链</b>——避免产生一条注定在第一步装配就失败的 zombie 链（specs/0007 §6.3）；</li>
 *   <li>{@code GET /api/v1/flows/{workflowId}} —— 回读编排链状态与结果（{@link FlowQueryService}）。</li>
 * </ul>
 *
 * <p>坐标口径复用单一事实源（不自造字面量副本）：{@code listing_id = PublishRuntime.workflowIdFor}、
 * {@code flow_workflow_id = ListingFlowRuntime.workflowIdFor}（CONTEXT.md「链路坐标」）。
 */
@RestController
@RequestMapping("/api/v1")
public class FlowController {

    private final CatalogIngestService ingestService;
    private final ListingFlowWorkflowLauncher flowLauncher;
    private final FlowQueryService flowQueryService;

    public FlowController(CatalogIngestService ingestService,
                          ListingFlowWorkflowLauncher flowLauncher,
                          FlowQueryService flowQueryService) {
        this.ingestService = Objects.requireNonNull(ingestService, "CatalogIngestService 必填");
        this.flowLauncher = Objects.requireNonNull(flowLauncher, "ListingFlowWorkflowLauncher 必填");
        this.flowQueryService = Objects.requireNonNull(flowQueryService, "FlowQueryService 必填");
    }

    /** 同步采集 + 异步启动编排链，返回 {@code 202 Accepted} 与链路坐标。 */
    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        SourceRef sourceRef = requireSourceRef(request.sourceRef());

        // 1) 同步采集（REST 线程占用一次 HTTP 调用量级；失败即抛 AdapterException，不落库）
        IngestResult ingested = ingestService.ingest(sourceRef);

        // 2) 采集成功后才异步启动编排链（WorkflowClient.start 非阻塞，不等链路跑完）
        flowLauncher.start(
                ingested.spuId(),
                request.channelId(),
                request.targetCategory(),
                List.copyOf(request.locales()),
                request.chain().toContentChainKind());

        String listingId = PublishRuntime.workflowIdFor(ingested.spuId(), request.channelId());
        String flowWorkflowId = ListingFlowRuntime.workflowIdFor(ingested.spuId(), request.channelId());
        return ResponseEntity.accepted().body(new IngestResponse(
                ingested.spuId(), listingId, flowWorkflowId, ingested.skuIds(), ingested.mediaIds()));
    }

    /** 回读编排链状态与结果。 */
    @GetMapping("/flows/{workflowId}")
    public FlowQueryResponse flow(@PathVariable String workflowId) {
        return flowQueryService.query(workflowId);
    }

    /**
     * {@code source_ref} 内部必填字段复核（core 记录无校验注解，specs/0007 §6.3「缺必填 → 400」）：
     * 任缺一即 400，避免 mapping 期 {@link IllegalArgumentException} 冒泡成 500。
     */
    private static SourceRef requireSourceRef(SourceRef ref) {
        if (ref == null || isBlank(ref.platform()) || isBlank(ref.externalId()) || isBlank(ref.fetchedAt())) {
            throw new InvalidRequestException(
                    "source_ref 需含非空 platform / external_id / fetched_at");
        }
        return ref;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
