package io.autocommerce.api;

import io.autocommerce.api.support.ProbeFlow;
import io.autocommerce.api.support.ScriptedOfferFetch;
import io.autocommerce.api.support.TemporalTestSupport;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code api} REST 入口测试（specs/0007 §6 / issue #73）：{@code @SpringBootTest} + WebTestClient，
 * 覆盖三条主线——
 *
 * <ol>
 *   <li><b>装配可达性</b>：真实 Spring 上下文加载 + 真实 HTTP 端口，{@code POST /api/v1/ingest} 走
 *       完整装配（controller → CatalogIngestService → WebTestClient 回读响应）；</li>
 *   <li><b>错误映射</b>：正常 → {@code 202}；{@code RETRYABLE} → {@code 503}；{@code NON_RETRYABLE}
 *       → {@code 422}；请求体校验失败 → {@code 400}；未知 workflowId → {@code 404}；</li>
 *   <li><b>无 zombie 链</b>：失败路径**真查 Temporal**——反查确定性 workflowId 不存在
 *       （{@code WorkflowNotFoundException}），而非只断言 HTTP 状态码。</li>
 * </ol>
 *
 * <p>外部边界（1688 offer fetch）用 {@link ScriptedOfferFetch}（手写 test fake，非 mock 框架）；
 * Temporal 用 in-process {@code TestWorkflowEnvironment}（{@link TemporalTestSupport}）；查询端点的
 * RUNNING / COMPLETED / FAILED 状态映射由探针 workflow（{@link ProbeFlow}）真实驱动。
 */
@SpringBootTest(classes = ApiTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestAndFlowQueryApiTest {

    private static final String CHANNEL_ID = "taobao-a";

    @LocalServerPort
    private int port;

    @Autowired
    private ScriptedOfferFetch offerFetch;

    @Autowired
    private WorkflowClient client;

    private WebTestClient web;

    @BeforeEach
    void setUp() {
        this.web = WebTestClient.bindToServer(new JdkClientHttpConnector())
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ---------------- POST /api/v1/ingest ----------------

    @Test
    void ingest_returns202WithCoordinates_andStartsFlowChain() {
        offerFetch.script(ScriptedOfferFetch.Mode.SUCCESS);

        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ingestBody("7001", "domestic"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.spu_id").isEqualTo("spu-1688-7001")
                .jsonPath("$.listing_id").isEqualTo("listing-spu-1688-7001-taobao-a")
                .jsonPath("$.flow_workflow_id").isEqualTo("fulfillment-spu-1688-7001-taobao-a")
                .jsonPath("$.sku_ids[0]").isEqualTo("sku-1688-7001-sku-src-1")
                .jsonPath("$.media_ids[0]").isEqualTo("media-1688-7001-0");

        // 编排链已**异步**启动（未注册 flow worker ⇒ 停在 RUNNING，正说明 HTTP 未等待链跑完）
        assertThat(describeStatus("fulfillment-spu-1688-7001-taobao-a"))
                .as("采集成功后编排链应被启动")
                .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
    }

    @Test
    void ingest_retryableAdapterFailure_returns503_andStartsNoChain() {
        offerFetch.script(ScriptedOfferFetch.Mode.RETRYABLE);
        int before = offerFetch.calls();

        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ingestBody("7002", "domestic"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("adapter_error")
                .jsonPath("$.platform_code").isEqualTo("THROTTLED");

        assertThat(offerFetch.calls()).isEqualTo(before + 1);
        assertNoFlowChain("spu-1688-7002");
    }

    @Test
    void ingest_nonRetryableAdapterFailure_returns422_andStartsNoChain() {
        offerFetch.script(ScriptedOfferFetch.Mode.NON_RETRYABLE);
        int before = offerFetch.calls();

        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ingestBody("7003", "cross_border"))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("adapter_error")
                .jsonPath("$.platform_code").isEqualTo("INVALID_QUALIFICATION");

        assertThat(offerFetch.calls()).isEqualTo(before + 1);
        assertNoFlowChain("spu-1688-7003");
    }

    @Test
    void ingest_invalidEnumChain_returns400_andStartsNoChain() {
        int before = offerFetch.calls();

        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ingestBody("7004", "bogus"))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(offerFetch.calls()).as("校验失败：采集根本不该被调用").isEqualTo(before);
        assertNoFlowChain("spu-1688-7004");
    }

    @Test
    void ingest_missingRequiredField_returns400_andNeverTouchesIngest() {
        int before = offerFetch.calls();
        String body = """
                {
                  "source_ref": {"platform":"1688","external_id":"7005",
                                 "url":"https://detail.1688.com/offer/7005.html",
                                 "fetched_at":"2026-09-10T00:00:00Z"},
                  "target_category": {"taxonomy":"taobao","value":"5001","label":"数码/影音"},
                  "locales": ["zh-CN"],
                  "chain": "domestic"
                }
                """; // 缺 channel_id

        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(offerFetch.calls()).isEqualTo(before);
    }

    /**
     * 重复提交同 {@code (spu_id, channel_id)} → {@code 409 Conflict}（而非幂等 202），且**不产生新链**：
     * 既有链的 runId 保持不变（真查 Temporal），状态码之外的语义也被钉住。
     *
     * <p>语义依据：冲突策略命中既有链时本次**并未启动任何新链**，{@code 202} 会谎报「刚触发一条新链」；
     * {@code 409} 如实表达冲突，并在错误体带既有链坐标 {@code flow_workflow_id} 供回读。
     */
    @Test
    void ingest_duplicateSubmission_returns409WithExistingCoordinates_andStartsNoNewChain() {
        offerFetch.script(ScriptedOfferFetch.Mode.SUCCESS);
        String externalId = "7010";
        String flowWorkflowId = io.autocommerce.worker.flow.ListingFlowRuntime
                .workflowIdFor("spu-1688-" + externalId, CHANNEL_ID);

        // 首次提交：采集成功 → 异步启动编排链（未注册 flow worker ⇒ 停在 RUNNING）
        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ingestBody(externalId, "domestic"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.flow_workflow_id").isEqualTo(flowWorkflowId);

        assertThat(describeStatus(flowWorkflowId))
                .as("首次提交后编排链应被启动")
                .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        String runIdAfterFirst = describeRunId(flowWorkflowId);

        // 二次提交同一 (spuId, channelId)：链路已存在 → 409（不是 202，不谎报"起了新链"）
        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ingestBody(externalId, "domestic"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("flow_already_exists")
                .jsonPath("$.flow_workflow_id").isEqualTo(flowWorkflowId);

        // 真查 Temporal：既有链未被替换（runId 不变 ⇒ 没有起新 run），状态也没被改
        assertThat(describeStatus(flowWorkflowId))
                .as("409 不得改变既有链状态")
                .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        assertThat(describeRunId(flowWorkflowId))
                .as("409 不得启动新 run：既有链 runId 应保持不变")
                .isEqualTo(runIdAfterFirst);
    }

    /**
     * {@code target_category} 内部必填字段（taxonomy / value）复核与 {@code source_ref} 同口径：缺即
     * {@code 400}，且**不该触碰采集**（校验先于 ingest）。{@code label} 是展示名缓存、非真源，故不校验。
     */
    @Test
    void ingest_blankTargetCategoryValue_returns400_andNeverTouchesIngest() {
        int before = offerFetch.calls();
        String body = """
                {
                  "source_ref": {"platform":"1688","external_id":"7011",
                                 "url":"https://detail.1688.com/offer/7011.html",
                                 "fetched_at":"2026-09-10T00:00:00Z"},
                  "channel_id": "%s",
                  "target_category": {"taxonomy":"taobao","value":"   ","label":"数码/影音"},
                  "locales": ["zh-CN"],
                  "chain": "domestic"
                }
                """.formatted(CHANNEL_ID);

        web.post().uri("/api/v1/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request");

        assertThat(offerFetch.calls()).as("target_category 校验失败：采集根本不该被调用").isEqualTo(before);
        assertNoFlowChain("spu-1688-7011");
    }

    // ---------------- GET /api/v1/flows/{workflowId} ----------------

    @Test
    void query_runningWorkflow_returns200WithRunningStatus() {
        String workflowId = "fulfillment-probe-running-taobao-a";
        startProbe(workflowId, "hang");
        awaitStatus(workflowId, WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

        web.get().uri("/api/v1/flows/{id}", workflowId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workflow_id").isEqualTo(workflowId)
                .jsonPath("$.status").isEqualTo("RUNNING");
    }

    @Test
    void query_completedWorkflow_returns200WithResult() {
        String workflowId = "fulfillment-probe-completed-taobao-a";
        startProbe(workflowId, "complete");
        awaitStatus(workflowId, WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);

        web.get().uri("/api/v1/flows/{id}", workflowId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.spu_id").isEqualTo("spu-probe")
                .jsonPath("$.listing_id").isEqualTo("listing-probe")
                .jsonPath("$.published").isEqualTo(true)
                .jsonPath("$.content_ready").isEqualTo(true)
                .jsonPath("$.platform_item_id").isEqualTo("item-1")
                .jsonPath("$.platform_item_url").isEqualTo("https://probe.example.com/item/1");
    }

    @Test
    void query_failedWorkflow_returns200WithFailedStatus_distinctFromRunning() {
        String workflowId = "fulfillment-probe-failed-taobao-a";
        startProbe(workflowId, "fail");
        awaitStatus(workflowId, WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED);

        web.get().uri("/api/v1/flows/{id}", workflowId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FAILED")
                .jsonPath("$.reason").isNotEmpty();
    }

    @Test
    void query_unknownWorkflow_returns404() {
        web.get().uri("/api/v1/flows/{id}", "fulfillment-does-not-exist-taobao-a")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("flow_not_found");
    }

    // ---------------- helpers ----------------

    private static String ingestBody(String externalId, String chain) {
        return """
                {
                  "source_ref": {"platform":"1688","external_id":"%s",
                                 "url":"https://detail.1688.com/offer/%s.html",
                                 "fetched_at":"2026-09-10T00:00:00Z"},
                  "channel_id": "%s",
                  "target_category": {"taxonomy":"taobao","value":"5001","label":"数码/影音"},
                  "locales": ["zh-CN"],
                  "chain": "%s"
                }
                """.formatted(externalId, externalId, CHANNEL_ID, chain);
    }

    /**
     * 「无 zombie 链」断言：反查确定性 workflowId 在 Temporal **不存在**（真查服务端，非只看状态码）。
     */
    private void assertNoFlowChain(String spuId) {
        String flowWorkflowId = io.autocommerce.worker.flow.ListingFlowRuntime
                .workflowIdFor(spuId, CHANNEL_ID);
        assertThatThrownBy(() -> client.newUntypedWorkflowStub(flowWorkflowId).describe())
                .as("采集/校验失败的请求绝不应启动编排链：" + flowWorkflowId)
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    private void startProbe(String workflowId, String mode) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TemporalTestSupport.PROBE_TASK_QUEUE)
                .build();
        ProbeFlow flow = client.newWorkflowStub(ProbeFlow.class, options);
        WorkflowClient.start(flow::run, mode);
    }

    private void awaitStatus(String workflowId, WorkflowExecutionStatus expected) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        WorkflowExecutionStatus last = null;
        while (System.currentTimeMillis() < deadline) {
            last = describeStatus(workflowId);
            if (last == expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断", e);
            }
        }
        throw new AssertionError("等待状态超时：期望 " + expected + "，实际 " + last + "（" + workflowId + "）");
    }

    private WorkflowExecutionStatus describeStatus(String workflowId) {
        return client.newUntypedWorkflowStub(workflowId).describe().getStatus();
    }

    /** 既有 execution 的 runId（真查 Temporal）——用于断言「没有起新 run」。 */
    private String describeRunId(String workflowId) {
        return client.newUntypedWorkflowStub(workflowId).describe().getExecution().getRunId();
    }
}
