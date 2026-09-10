package io.autocommerce.content.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.step.ChatMessage;
import io.autocommerce.core.step.ChatRequest;
import io.autocommerce.core.step.ChatResponse;
import io.autocommerce.core.step.ChatUsage;
import io.autocommerce.core.step.LLMProvider;
import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.ProviderId;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;

/**
 * v1 唯一内置 {@link LLMProvider} 实现（specs/0006 §4）：OpenAI-compatible HTTP 直连，不引 SDK。
 *
 * <p>{@code base_url + api_key + model} 三项配置即覆盖 OpenAI / 本地（Ollama、vLLM）/ 国产
 * （通义、DeepSeek）等绝大多数后端；特殊协议平台未来写独立 provider（SPI），core 不逐平台适配。
 *
 * <p>失败一律收敛为 {@link ProviderException}（HTTP 非 2xx / 超时 / 连接失败 / 响应缺 choices）——
 * 重试与超时兜底由内容链 Activity 的 RetryPolicy 与 {@code ScheduleToCloseTimeout} 承担
 * （specs/0006 §5）；provider 内部不重试，避免与编排层重试叠加。
 */
public final class OpenAICompatProvider implements LLMProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenAICompatConfig config;
    private final HttpClient http;

    public OpenAICompatProvider(OpenAICompatConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** 测试/高级装配可注入 HttpClient（如自定义连接池）；默认实现见上。 */
    public OpenAICompatProvider(OpenAICompatConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public ProviderId id() {
        return config.providerId();
    }

    @Override
    public ChatResponse chat(ChatRequest request) throws ProviderException {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new ProviderException("ChatRequest.messages 必填");
        }
        String model = request.model() == null || request.model().isBlank()
                ? config.defaultModel()
                : request.model();
        if (model.isBlank()) {
            throw new ProviderException("未指定 model，且 provider 无默认 model（llm.model 未配置）");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        try {
            if (!config.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + config.apiKey());
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(body(model, request)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ProviderException("LLM 端点 HTTP " + response.statusCode() + ": "
                        + snippet(response.body()));
            }
            return parse(model, response.body());
        } catch (HttpTimeoutException e) {
            throw new ProviderException("LLM 调用超时（" + config.timeout().toSeconds() + "s）: "
                    + config.baseUrl(), e);
        } catch (IOException e) {
            throw new ProviderException("LLM 端点不可达: " + config.baseUrl() + "（" + e.getMessage() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("LLM 调用被中断: " + config.baseUrl(), e);
        }
    }

    private static String body(String model, ChatRequest request) throws ProviderException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.messages()) {
            if (message == null || message.role() == null) {
                throw new ProviderException("ChatMessage.role 必填");
            }
            ObjectNode node = messages.addObject();
            node.put("role", message.role().name().toLowerCase(Locale.ROOT));
            node.set("content", MAPPER.valueToTree(message.content()));
        }
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new ProviderException("构造 chat/completions 请求体失败", e);
        }
    }

    private static ChatResponse parse(String requestedModel, String body) throws ProviderException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ProviderException("LLM 响应非 JSON: " + snippet(body), e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ProviderException("LLM 响应缺 choices: " + snippet(body));
        }
        JsonNode message = choices.get(0).path("message");
        if (message.isMissingNode() || !message.path("content").isTextual()) {
            throw new ProviderException("LLM 响应缺 choices[0].message.content: " + snippet(body));
        }
        JsonNode usage = root.path("usage");
        ChatUsage chatUsage = usage.isObject()
                ? new ChatUsage(integer(usage, "prompt_tokens"), integer(usage, "completion_tokens"),
                        integer(usage, "total_tokens"))
                : null;
        String model = root.path("model").isTextual() ? root.get("model").asText() : requestedModel;
        return new ChatResponse(message.get("content").asText(), model, chatUsage);
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.intValue() : null;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "<empty>";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }
}
