package io.autocommerce.content.spi;

import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.AiStepProvider;
import io.autocommerce.core.step.LLMProvider;
import io.autocommerce.core.step.MediaProcessor;
import io.autocommerce.core.step.ModelResolver;
import io.autocommerce.core.step.ResolvedModel;
import io.autocommerce.content.ai.DescGenerateStep;
import io.autocommerce.content.ai.I18nBackfillStep;
import io.autocommerce.content.ai.LlmGateway;
import io.autocommerce.content.ai.MediaProcessStep;
import io.autocommerce.content.ai.PriceStrategyStep;
import io.autocommerce.content.ai.TitleRewriteStep;
import io.autocommerce.content.media.ArchiveMediaProcessor;
import io.autocommerce.content.provider.OpenAICompatConfig;
import io.autocommerce.content.provider.OpenAICompatProvider;
import io.autocommerce.content.provider.ProviderRegistry;
import io.autocommerce.content.provider.ResolvingLlmGateway;
import io.autocommerce.content.resolver.StaticModelResolver;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 首批 AI Step 的 SPI 声明（specs/0006 §3 / #20 AC-2）：注册文件
 * {@code META-INF/services/io.autocommerce.core.step.AiStepProvider}，classpath 上存在即被发现
 * ——**无中央注册表**（与 Adapter 插件同哲学，ADR-0007/#9）；装配点（adapter-host）经 ServiceLoader
 * 汇总各 provider 的 Step。
 *
 * <p>Step 是**无状态插件**：各自声明 {@code id / input / output / model_requirement}，读标准模型字段、
 * 写回标准模型字段，**不传中间 DTO**（specs/0006 §3）。依赖（LLM 网关 / 媒体处理器）经构造函数注入，
 * 无参构造即"生产默认装配"（配置取环境变量），使 ServiceLoader 的发现路径与显式装配路径共用同一批 Step。
 */
public final class ContentAiStepProvider implements AiStepProvider {

    /** 媒体归档根目录（生产默认 {@code target/media}；容器部署建议指向挂载卷）。 */
    public static final String ENV_MEDIA_ROOT = "FLOWCART_MEDIA_ROOT";

    private static final String DEFAULT_MEDIA_ROOT = "target/media";

    private final List<AiStep> steps;

    /** ServiceLoader 入口：默认装配（LLM 配置与环境变量，媒体根目录取 {@link #ENV_MEDIA_ROOT}）。 */
    public ContentAiStepProvider() {
        this(defaultLlmGateway(), defaultMediaProcessor());
    }

    /** 显式装配（测试 / composition root 注入自有 gateway 与 processor）。 */
    public ContentAiStepProvider(LlmGateway llmGateway, MediaProcessor mediaProcessor) {
        this.steps = List.of(
                new I18nBackfillStep(llmGateway),
                new TitleRewriteStep(llmGateway),
                new DescGenerateStep(llmGateway),
                new PriceStrategyStep(),
                new MediaProcessStep(mediaProcessor));
    }

    /** 从配置表 + 媒体根目录装配（装配点/集成测试用；配置键见 {@link OpenAICompatConfig}）。 */
    public static ContentAiStepProvider of(Map<String, String> config, Path mediaRoot) {
        return new ContentAiStepProvider(llmGateway(config), new ArchiveMediaProcessor(mediaRoot));
    }

    /** 由配置构造 LLM 网关：OpenAI-compatible provider + v1 静态解析点（specs/0006 §4）。 */
    public static LlmGateway llmGateway(Map<String, String> config) {
        OpenAICompatConfig llmConfig = OpenAICompatConfig.from(config);
        LLMProvider provider = new OpenAICompatProvider(llmConfig);
        ModelResolver resolver = StaticModelResolver.singleLlm(
                new ResolvedModel(llmConfig.providerId(), llmConfig.defaultModel()));
        return new ResolvingLlmGateway(resolver, ProviderRegistry.of(provider));
    }

    @Override
    public List<AiStep> steps() {
        return steps;
    }

    private static LlmGateway defaultLlmGateway() {
        return llmGateway(System.getenv());
    }

    private static MediaProcessor defaultMediaProcessor() {
        String root = System.getenv(ENV_MEDIA_ROOT);
        return new ArchiveMediaProcessor(Path.of(root == null || root.isBlank() ? DEFAULT_MEDIA_ROOT : root));
    }
}
