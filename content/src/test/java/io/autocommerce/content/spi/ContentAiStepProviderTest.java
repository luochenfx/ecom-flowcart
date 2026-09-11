package io.autocommerce.content.spi;

import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.AiStepProvider;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.StepDescriptor;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.testsupport.FakeLlmGateway;
import io.autocommerce.content.testsupport.FakeMediaProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首批 AI Step 的 SPI 注册与声明（specs/0006 §3 / #20 AC-2）：Java SPI 无中央注册表
 * （classpath 存在即被发现），每个 Step 声明 id / 字段级 input-output / model_requirement 三分。
 */
class ContentAiStepProviderTest {

    private static List<AiStepProvider> loadProviders() {
        return ServiceLoader.load(AiStepProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    @Test
    void discoveredViaServiceLoader_singleProviderDeclaringFirstBatchSteps() {
        List<AiStepProvider> providers = loadProviders();

        assertThat(providers).hasSize(1).first().isInstanceOf(ContentAiStepProvider.class);
        assertThat(providers.get(0).steps()).extracting(s -> s.descriptor().id())
                .containsExactlyInAnyOrder(
                        ContentPlan.I18N_BACKFILL, ContentPlan.TITLE_REWRITE, ContentPlan.DESC_GENERATE,
                        ContentPlan.PRICE_STRATEGY, ContentPlan.MEDIA_PROCESS);
    }

    @Test
    void everyStepDeclaresFieldLevelContractAndModelRequirement() {
        List<StepDescriptor> descriptors = loadProviders().get(0).steps().stream()
                .map(AiStep::descriptor).toList();

        assertThat(descriptors).allSatisfy(d -> {
            assertThat(d.input()).as("%s input 非空（字段级读写声明）", d.id()).isNotEmpty();
            assertThat(d.output()).as("%s output 非空", d.id()).isNotEmpty();
            assertThat(d.modelRequirement()).as("%s model_requirement 必填", d.id()).isNotNull();
        });
        // 三分齐全：LLM ×3 / RULE ×1 / MEDIA_PROCESSOR ×1（价格可纯规则、媒体与 LLM 分型）
        assertThat(descriptors).extracting(StepDescriptor::modelRequirement)
                .contains(ModelRequirement.LLM, ModelRequirement.RULE, ModelRequirement.MEDIA_PROCESSOR);
        assertThat(descriptors.stream().filter(d -> d.modelRequirement() == ModelRequirement.LLM).count())
                .isEqualTo(3);
        // 声明可被编排消费：计划引用的 Step 全部在册
        Set<String> declared = descriptors.stream().map(StepDescriptor::id).collect(Collectors.toSet());
        assertThat(ContentPlan.standard().stepIds()).allMatch(declared::contains);
    }

    @Test
    void explicitWiring_supportsInjectedCollaborators() {
        ContentAiStepProvider provider = new ContentAiStepProvider(new FakeLlmGateway(), new FakeMediaProcessor());

        assertThat(provider.steps()).hasSize(5);
        assertThat(provider.steps()).extracting(s -> s.descriptor().id()).doesNotHaveDuplicates();
    }
}
