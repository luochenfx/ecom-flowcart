package io.autocommerce.content.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.autocommerce.core.step.ChatMessage;
import io.autocommerce.core.step.ChatRequest;
import io.autocommerce.core.step.ChatResponse;
import io.autocommerce.core.step.ChatRole;
import io.autocommerce.core.step.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI-compatible 直连（specs/0006 §4）：请求体对齐 chat/completions、响应含 usage、
 * 失败收敛为 ProviderException。用 WireMock 模拟端点，无需真实模型。
 */
class OpenAICompatProviderTest {

    private static final String PATH = "/chat/completions";

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private OpenAICompatProvider provider(Map<String, String> overrides) {
        Map<String, String> config = new java.util.LinkedHashMap<>();
        config.put("llm.base_url", "http://localhost:" + server.port());
        config.put("llm.model", "test-model");
        config.putAll(overrides);
        return new OpenAICompatProvider(OpenAICompatConfig.from(config));
    }

    private static ChatRequest request(String model) {
        return new ChatRequest(model,
                List.of(ChatMessage.of(ChatRole.SYSTEM, "system"), ChatMessage.of(ChatRole.USER, "user")),
                0.5, 128);
    }

    @Test
    void parsesContentModelAndUsage_andSendsOpenAiShapedBody() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"gpt-4o-mini","choices":[{"message":{"role":"assistant",
                        "content":"你好，世界"}}],
                        "usage":{"prompt_tokens":11,"completion_tokens":22,"total_tokens":33}}
                        """)));

        ChatResponse response = provider(Map.of("llm.api_key", "sk-secret")).chat(request("gpt-4o-mini"));

        assertThat(response.content()).isEqualTo("你好，世界");
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        assertThat(response.usage().totalTokens()).isEqualTo(33);

        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-secret"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(containing("\"model\":\"gpt-4o-mini\""))
                .withRequestBody(containing("\"role\":\"user\""))
                .withRequestBody(containing("\"max_tokens\":128")));
    }

    @Test
    void usesDefaultModel_whenRequestDoesNotSpecifyOne() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")));

        ChatResponse response = provider(Map.of()).chat(request(null));

        assertThat(response.content()).isEqualTo("ok");
        assertThat(response.model()).isEqualTo("test-model");
        assertThat(response.usage()).isNull();
        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(containing("\"model\":\"test-model\"")));
    }

    @Test
    void non2xx_isProviderException() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429).withBody("rate limited")));

        assertThatThrownBy(() -> provider(Map.of()).chat(request("m")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("rate limited");
    }

    @Test
    void malformedResponse_isProviderException() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{\"id\":\"x\"}")));

        assertThatThrownBy(() -> provider(Map.of()).chat(request("m")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("choices");
    }

    @Test
    void unreachableEndpoint_isProviderException() {
        OpenAICompatConfig config = OpenAICompatConfig.from(Map.of(
                "llm.base_url", "http://localhost:9/v1", "llm.model", "m"));

        assertThatThrownBy(() -> new OpenAICompatProvider(config).chat(request(null)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("不可达");
    }
}
