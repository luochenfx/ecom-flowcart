package io.autocommerce.content.provider;

import io.autocommerce.core.step.ProviderId;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI-compatible provider 配置（specs/0006 §4 / #20 AC-4）：{@code base_url} / {@code api_key} /
 * {@code model} 三项配置化即覆盖 OpenAI 与本地兼容端点（Ollama / vLLM / 通义 / DeepSeek 皆兼容
 * OpenAI 协议）——"配置即换后端"，不引任何厂商 SDK。
 *
 * <p>键位支持两种书写，便于容器与本地开发：
 * <ul>
 *   <li>{@code llm.base_url} / {@code llm.api_key} / {@code llm.model}（properties / 配置表）</li>
 *   <li>{@code FLOWCART_LLM_BASE_URL} / {@code FLOWCART_LLM_API_KEY} / {@code FLOWCART_LLM_MODEL}
 *       （环境变量 / docker-compose .env）</li>
 * </ul>
 *
 * <p><b>凭据现状（已知缺口，非本票范围）</b>：v1 的 {@code api_key} 来自配置表 / 环境变量（明文），
 * 尚未接入 specs/0006 §10 规划的 CredentialStore（AES 落库 + CredentialView）。provider 是系统级凭据
 * （非 channel 级），接入时在 {@code from(...)} 一处换源，其余调用方不感知。
 *
 * @param providerId   provider 标识（{@code ResolvedModel.provider_id} 的匹配键）
 * @param baseUrl      OpenAI-compatible 根地址（不含 {@code /chat/completions}；结尾斜杠自动规范）
 * @param apiKey       凭据；本地端点可空（空 = 不发 Authorization 头）
 * @param defaultModel 默认模型名（Step 未指定 model 时用）
 * @param timeout      单次调用超时（Activity 侧还有 ScheduleToCloseTimeout 兜总时长）
 */
public record OpenAICompatConfig(ProviderId providerId, String baseUrl, String apiKey, String defaultModel,
                                 Duration timeout) {

    public static final String KEY_PROVIDER_ID = "llm.provider_id";
    public static final String KEY_BASE_URL = "llm.base_url";
    public static final String KEY_API_KEY = "llm.api_key";
    public static final String KEY_MODEL = "llm.model";
    public static final String KEY_TIMEOUT_SECONDS = "llm.timeout_seconds";

    public static final String ENV_PROVIDER_ID = "FLOWCART_LLM_PROVIDER_ID";
    public static final String ENV_BASE_URL = "FLOWCART_LLM_BASE_URL";
    public static final String ENV_API_KEY = "FLOWCART_LLM_API_KEY";
    public static final String ENV_MODEL = "FLOWCART_LLM_MODEL";
    public static final String ENV_TIMEOUT_SECONDS = "FLOWCART_LLM_TIMEOUT_SECONDS";

    /** 默认端点 = 本地 Ollama 的 OpenAI 兼容口（本地起即可跑通，无需真实模型账号）。 */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";
    public static final String DEFAULT_MODEL = "qwen2.5:7b";
    public static final long DEFAULT_TIMEOUT_SECONDS = 60L;

    public OpenAICompatConfig {
        Objects.requireNonNull(providerId, "provider_id 必填");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("llm.base_url 必填（OpenAI-compatible 根地址）");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("llm.base_url 须为 http(s) 地址: " + baseUrl);
        }
        baseUrl = normalized;
        apiKey = apiKey == null ? "" : apiKey;
        defaultModel = defaultModel == null ? "" : defaultModel;
        timeout = timeout == null ? Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("llm.timeout_seconds 必须为正");
        }
    }

    /** 从配置表/环境变量装载（缺项取默认；键位见类 javadoc）。 */
    public static OpenAICompatConfig from(Map<String, String> config) {
        Map<String, String> source = config == null ? Map.of() : config;
        String timeoutRaw = firstNonBlank(source, KEY_TIMEOUT_SECONDS, ENV_TIMEOUT_SECONDS);
        long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (timeoutRaw != null) {
            try {
                timeoutSeconds = Long.parseLong(timeoutRaw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("llm.timeout_seconds 非整数: " + timeoutRaw, e);
            }
        }
        String providerId = firstNonBlank(source, KEY_PROVIDER_ID, ENV_PROVIDER_ID);
        return new OpenAICompatConfig(
                new ProviderId(providerId == null ? "openai" : providerId),
                orDefault(firstNonBlank(source, KEY_BASE_URL, ENV_BASE_URL), DEFAULT_BASE_URL),
                orDefault(firstNonBlank(source, KEY_API_KEY, ENV_API_KEY), ""),
                orDefault(firstNonBlank(source, KEY_MODEL, ENV_MODEL), DEFAULT_MODEL),
                Duration.ofSeconds(timeoutSeconds));
    }

    /** 从进程环境变量装载（容器部署形态）。 */
    public static OpenAICompatConfig fromEnvironment() {
        return from(System.getenv());
    }

    /** 回显用配置快照（api_key 只报是否已配，不回显明文）。 */
    public Map<String, String> describe() {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("provider_id", providerId.value());
        view.put("base_url", baseUrl);
        view.put("model", defaultModel);
        view.put("api_key_configured", Boolean.toString(!apiKey.isBlank()));
        view.put("timeout_seconds", Long.toString(timeout.toSeconds()));
        return view;
    }

    private static String firstNonBlank(Map<String, String> config, String... keys) {
        for (String key : keys) {
            String value = config.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
