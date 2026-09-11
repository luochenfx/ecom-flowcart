package io.autocommerce.content.provider;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LLM 后端配置化（#20 AC-4）：base_url / api_key / model 三项即换后端。 */
class OpenAICompatConfigTest {

    @Test
    void defaults_targetLocalOpenAiCompatibleEndpoint() {
        OpenAICompatConfig config = OpenAICompatConfig.from(Map.of());

        assertThat(config.providerId().value()).isEqualTo("openai");
        assertThat(config.baseUrl()).isEqualTo(OpenAICompatConfig.DEFAULT_BASE_URL);
        assertThat(config.defaultModel()).isEqualTo(OpenAICompatConfig.DEFAULT_MODEL);
        assertThat(config.apiKey()).isEmpty();
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void readsDottedKeys_andEnvironmentStyleKeys() {
        OpenAICompatConfig dotted = OpenAICompatConfig.from(Map.of(
                "llm.provider_id", "local-ollama",
                "llm.base_url", "http://localhost:11434/v1",
                "llm.model", "qwen2.5:14b",
                "llm.timeout_seconds", "30"));

        assertThat(dotted.providerId().value()).isEqualTo("local-ollama");
        assertThat(dotted.defaultModel()).isEqualTo("qwen2.5:14b");
        assertThat(dotted.timeout()).isEqualTo(Duration.ofSeconds(30));

        OpenAICompatConfig envStyle = OpenAICompatConfig.from(Map.of(
                "FLOWCART_LLM_BASE_URL", "https://api.deepseek.com/v1",
                "FLOWCART_LLM_API_KEY", "sk-test",
                "FLOWCART_LLM_MODEL", "deepseek-chat"));

        assertThat(envStyle.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(envStyle.apiKey()).isEqualTo("sk-test");
        // 快照不回显 api_key 明文
        assertThat(envStyle.describe()).containsEntry("api_key_configured", "true")
                .doesNotContainValue("sk-test");
    }

    @Test
    void normalizesTrailingSlash_andRejectsBadInput() {
        assertThat(OpenAICompatConfig.from(Map.of("llm.base_url", "http://host:8080/v1/")).baseUrl())
                .isEqualTo("http://host:8080/v1");

        assertThatThrownBy(() -> OpenAICompatConfig.from(Map.of("llm.base_url", "host:8080/v1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http(s)");
        assertThatThrownBy(() -> OpenAICompatConfig.from(Map.of(
                "llm.base_url", "http://host/v1", "llm.timeout_seconds", "abc")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非整数");
    }
}
