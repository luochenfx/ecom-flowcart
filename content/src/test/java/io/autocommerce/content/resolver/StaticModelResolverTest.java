package io.autocommerce.content.resolver;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.ProviderId;
import io.autocommerce.core.step.ResolvedModel;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.step.ListingStepContext;
import io.autocommerce.content.testsupport.ContentDocs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型解析点 seam（specs/0006 §4 / #20 AC-7）：v1 静态映射；Step 可在 params 里覆盖模型档位；
 * 未配置的需求显式失败（编排插槽未来在同接口实现路由）。
 */
class StaticModelResolverTest {

    private final StaticModelResolver resolver = StaticModelResolver.singleLlm(
            new ResolvedModel(new ProviderId("openai"), "gpt-4o-mini"));

    private StepContext contextWithParams(com.fasterxml.jackson.databind.JsonNode params) {
        ContentWorkingSet working = ContentWorkingSet.of(ContentDocs.masterWithListing(), ContentDocs.listingId());
        return new ListingStepContext(working, params);
    }

    @Test
    void resolvesFromStaticMapping() {
        ResolvedModel resolved = resolver.resolve(ModelRequirement.LLM, contextWithParams(null));

        assertThat(resolved.providerId().value()).isEqualTo("openai");
        assertThat(resolved.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void stepParamsOverrideModelTier_withoutTouchingCode() {
        var params = JsonNodeFactory.instance.objectNode().put("model", "qwen2.5:14b");

        ResolvedModel resolved = resolver.resolve(ModelRequirement.LLM, contextWithParams(params));

        assertThat(resolved.model()).isEqualTo("qwen2.5:14b");
        assertThat(resolved.providerId().value()).isEqualTo("openai");
    }

    @Test
    void unconfiguredRequirement_isExplicitFailure() {
        assertThatThrownBy(() -> resolver.resolve(ModelRequirement.MEDIA_PROCESSOR, contextWithParams(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media_processor".toUpperCase(java.util.Locale.ROOT))
                .hasMessageContaining("未配置");
    }

    @Test
    void nullRequirement_isRejected() {
        assertThatThrownBy(() -> resolver.resolve(null, contextWithParams(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model_requirement");
        assertThat(List.of()).isEmpty();
    }
}
