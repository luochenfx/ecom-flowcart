package io.autocommerce.api.support;

import io.autocommerce.worker.flow.ListingFlowWorkflowResult;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

import java.util.List;

/** {@link ProbeFlow} 实现：按 {@code mode} 进入完成 / 失败 / 长期挂起三种状态。 */
public class ProbeFlowImpl implements ProbeFlow {

    @Override
    public ListingFlowWorkflowResult run(String mode) {
        return switch (mode) {
            case "complete" -> new ListingFlowWorkflowResult(
                    "spu-probe", "listing-probe", true,
                    "item-1", "https://probe.example.com/item/1", true, List.of(), null);
            case "fail" -> throw ApplicationFailure.newNonRetryableFailure(
                    "probe 失败（测试用）", "ProbeFailure");
            // 挂起等裁定：specs/0007 §4.5「长期挂起是合法状态」——Temporal 侧即 RUNNING
            case "hang" -> {
                Workflow.await(() -> false);
                yield null;
            }
            default -> throw ApplicationFailure.newNonRetryableFailure(
                    "未知 mode：" + mode, "ProbeBadMode");
        };
    }
}
