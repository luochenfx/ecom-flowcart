package io.autocommerce.content;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.FieldRef;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.core.step.StepDescriptor;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepOutcome;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.testsupport.ContentDocs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单步执行器的失败语义（specs/0006 §5 / #20 AC-6）：
 * 硬依赖失败 → 内容链 failed；非硬依赖失败 → 降级留痕、字段保持上游值、链路继续。
 */
class ContentStepExecutorTest {

    private static final ContentPlan.PlanStep CRITICAL = new ContentPlan.PlanStep("boom.step", true);
    private static final ContentPlan.PlanStep OPTIONAL = new ContentPlan.PlanStep("boom.step", false);

    @Test
    void criticalStepFailure_failsTheChain() {
        ContentStepExecutor executor = new ContentStepExecutor(List.of(new ThrowingStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());

        assertThatThrownBy(() -> executor.execute(working, CRITICAL))
                .isInstanceOf(ContentChainFailedException.class)
                .hasMessageContaining("boom.step")
                .hasMessageContaining("硬依赖");
    }

    @Test
    void optionalStepFailure_degradesAndContinues() {
        ContentStepExecutor executor = new ContentStepExecutor(List.of(new ThrowingStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());

        ContentStepRun run = executor.execute(working, OPTIONAL);

        assertThat(run.outcome()).isEqualTo(StepOutcome.DEGRADED);
        assertThat(working.degradedSteps()).hasSize(1);
        assertThat(working.degradedSteps().get(0).step()).isEqualTo("boom.step");
        assertThat(working.degradedSteps().get(0).reason()).contains("产物保持上游值");
    }

    @Test
    void degradedResultFromStep_isReturnedAsIs() {
        ContentStepExecutor executor = new ContentStepExecutor(
                List.of(new DegradingStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());

        ContentStepRun run = executor.execute(working, new ContentPlan.PlanStep("degrade.step", true));

        assertThat(run.outcome()).isEqualTo(StepOutcome.DEGRADED);
        assertThat(run.reason()).isEqualTo("缺省产物");
        // Step「返回 DEGRADED」与「抛异常的非硬依赖」同走执行器这一处登记口（degraded_steps 唯一写口）：
        // 降级留痕不依赖每个 Step 自觉，否则 specs/0006 §5 的留痕会在链上漏记。
        assertThat(working.degradedSteps()).hasSize(1);
        assertThat(working.degradedSteps().get(0).step()).isEqualTo("degrade.step");
        assertThat(working.degradedSteps().get(0).reason()).isEqualTo("缺省产物");
    }

    /**
     * 重跑恢复后必须清掉历史留痕：否则一份内容已补齐的 Listing 会一直对看板亮 HITL，
     * 人工复核队列被噪声淹没（specs/0006 §5 的 degraded_steps 语义 = 当前缺口，不是历史流水）。
     */
    @Test
    void successfulRerun_clearsStaleDegradedEntry() {
        ContentStepExecutor executor = new ContentStepExecutor(
                List.of(new RecoveringStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = workingWithStaleDegradation("recover.step");

        ContentStepRun run = executor.execute(working, new ContentPlan.PlanStep("recover.step", false));

        assertThat(run.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(working.degradedSteps()).isEmpty();
    }

    /** 不在本计划内的 Step：无法判定其缺口是否已补，保守保留留痕。 */
    @Test
    void untouchedStepKeepsItsDegradedEntry() {
        ContentStepExecutor executor = new ContentStepExecutor(
                List.of(new RecoveringStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = workingWithStaleDegradation("other.step");

        executor.execute(working, new ContentPlan.PlanStep("recover.step", false));

        assertThat(working.degradedSteps()).extracting("step").containsExactly("other.step");
    }

    private static ContentWorkingSet workingWithStaleDegradation(String stepId) {
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());
        working.addDegradedStep(stepId, "上一次运行失败");
        return working;
    }

    @Test
    void unknownStepInPlan_isAssemblyError_notSilentlySkipped() {
        ContentStepExecutor executor = new ContentStepExecutor(List.of(new DegradingStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());

        assertThatThrownBy(() -> executor.execute(working, new ContentPlan.PlanStep("nope.step", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未注册的 Step");
    }

    @Test
    void duplicateStepIds_rejectedAtAssembly() {
        assertThatThrownBy(() -> new ContentStepExecutor(
                List.of(new DegradingStep(), new DegradingStep()), ContentDocs.FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复注册");
    }

    @Test
    void materialize_stampsUpdatedAtFromInjectedClock() {
        ContentStepExecutor executor = new ContentStepExecutor(List.of(new DegradingStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());
        working.markListingTouched();

        assertThat(executor.materialize(working).listings().get(0).provenance().updatedAt())
                .isEqualTo("2026-09-10T12:00:00Z");
    }

    /**
     * 降级产物不污染 provenance：addDegradedStep 只登记降级留痕，不应触发 provenance 升为 AI
     * （specs/0006 §6 单稿制：物化只对真实写入盖章；缺省值不算 AI 写的）。
     * 否则运营看 Listing 时分不清"这个字段是 AI 改写的"vs"这是降级后用原文兜底"。
     */
    @Test
    void degradedStep_doesNotStampAiProvenanceOnListing() {
        ContentStepExecutor executor = new ContentStepExecutor(List.of(new DegradingStep()), ContentDocs.FIXED_CLOCK);
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());
        executor.execute(working, new ContentPlan.PlanStep("degrade.step", false));

        var listing = executor.materialize(working).listings().get(0);
        assertThat(listing.degradedSteps()).hasSize(1);
        assertThat(listing.provenance().updatedByStep())
                .as("仅降级留痕不触发 provenance 升 AI（降级产物 = 缺省/原文，非 AI 写）")
                .isNotEqualTo(ProvenanceStep.AI);
    }

    private static final class ThrowingStep implements AiStep {

        @Override
        public StepDescriptor descriptor() {
            return new StepDescriptor("boom.step", List.<FieldRef>of(), List.<FieldRef>of(),
                    ModelRequirement.RULE, JsonNodeFactory.instance.objectNode());
        }

        @Override
        public StepResult execute(StepContext context) {
            throw new StepExecutionException("上游端点不可用");
        }
    }

    private static final class DegradingStep implements AiStep {

        @Override
        public StepDescriptor descriptor() {
            return new StepDescriptor("degrade.step", List.<FieldRef>of(), List.<FieldRef>of(),
                    ModelRequirement.RULE, JsonNodeFactory.instance.objectNode());
        }

        @Override
        public StepResult execute(StepContext context) {
            return StepResult.degraded("缺省产物");
        }
    }

    /** 本轮成功的 Step（用于验证历史留痕被清除）。 */
    private static final class RecoveringStep implements AiStep {

        @Override
        public StepDescriptor descriptor() {
            return new StepDescriptor("recover.step", List.<FieldRef>of(), List.<FieldRef>of(),
                    ModelRequirement.RULE, JsonNodeFactory.instance.objectNode());
        }

        @Override
        public StepResult execute(StepContext context) {
            return StepResult.ok();
        }
    }
}
