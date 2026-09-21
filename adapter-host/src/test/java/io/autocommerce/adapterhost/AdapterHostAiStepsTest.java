package io.autocommerce.adapterhost;

import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.AiStepProvider;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.core.step.StepDescriptor;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdapterHost#aiSteps()} 单测（specs/0007 §7.1 / #71 AC）。
 *
 * <p>用<b>自定义 ClassLoader</b> 提供一份受控的 {@link AiStepProvider} SPI 声明——
 * 隔离 classpath，断言与测试环境变量无关。这验证的是"AdapterHost 经
 * {@code ServiceLoader.load(AiStepProvider.class)} 汇总各 provider 的 Step"这一装配行为，
 * 不依赖 content 模块的默认装配（其依赖环境变量，不宜作为断言目标）。
 */
class AdapterHostAiStepsTest {

    @Test
    void aggregatesStepsFromServiceLoaderProviders() throws IOException {
        Path classes = Files.createTempDirectory("adapterhost-aisteps");
        try {
            writeServiceFile(classes);
            try (URLClassLoader loader = new URLClassLoader(
                    new URL[]{classes.toUri().toURL()},
                    Thread.currentThread().getContextClassLoader())) {

                AdapterHost host = AdapterHost.load(loader);

                List<AiStep> steps = host.aiSteps();
                assertThat(steps).hasSize(2);
                assertThat(steps).extracting(s -> s.descriptor().id())
                        .containsExactly("test-step-a", "test-step-b");
            }
        } finally {
            deleteRecursively(classes);
        }
    }

    @Test
    void aiStepsIsImmutableAndEmptyWhenNoProvider() {
        // 用一个只含自身类、无 SPI 文件的 ClassLoader → 发现 0 个 Step（不抛异常）
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            AdapterHost host = AdapterHost.load(empty);
            assertThat(host.aiSteps()).isEmpty();
            assertThat(host.platforms()).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 受控 SPI fixture ----

    private static void writeServiceFile(Path classes) throws IOException {
        Path services = classes.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(
                services.resolve("io.autocommerce.core.step.AiStepProvider"),
                TestAiStepProvider.class.getName(),
                StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /** 声明两个 Step 的受控 provider（无参构造 = ServiceLoader 入口）。 */
    public static final class TestAiStepProvider implements AiStepProvider {
        public TestAiStepProvider() {
        }

        @Override
        public List<AiStep> steps() {
            return List.of(new TestStep("test-step-a"), new TestStep("test-step-b"));
        }
    }

    /** 最小可运行 Step：只回传 id，execute 为空操作。 */
    private static final class TestStep implements AiStep {
        private final StepDescriptor descriptor;

        TestStep(String id) {
            this.descriptor = new StepDescriptor(id, List.of(), List.of(),
                    ModelRequirement.RULE, null);
        }

        @Override
        public StepDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public StepResult execute(StepContext context) throws StepExecutionException {
            return StepResult.ok();
        }
    }
}
