package io.autocommerce.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>打包 {@code application.yml} 的守门测试</b>（#74 审查 Should③）：
 * 断言随 fat jar 发布的 {@code src/main/resources/application.yml}（生产唯一配置面）真的把生产装配指向
 * compose 的自托管 Temporal server，而不是断言测试自己 {@code set(...)} 进去的字面量。
 *
 * <h2>为什么必须有它</h2>
 * {@link TemporalConfigurationTest} 断言的目标来自测试内 {@code new AppProperties()} 后手工 set 的值；
 * {@code AppContextTest} / {@code AppContextWithoutWorkerRoleTest} 又把 {@code app.temporal.target}
 * 覆盖为 {@code 127.0.0.1:7233}——于是全仓<b>没有任何测试</b>读打包 yml。若该键被误删 / 写错，所有测试
 * 仍绿而生产装配静默连错目标（与本票陷阱 1「扫描盲区」、陷阱 3「逗号串」同型：<b>配置错、测试绿、运行期坏</b>）。
 *
 * <h2>机制</h2>
 * 用 {@link YamlPropertySourceLoader} 读 classpath 上的应用 yml（= jar 内同一份资源），经 Spring Boot 的
 * {@link Binder} 走<b>与生产相同的配置绑定链路</b>绑定成 {@link AppProperties}。只吃打包 yml 本身、不带
 * 环境变量 / 系统属性覆盖，故结论确定、不受 CI 环境影响。若 yml 中 {@code app.temporal.target} 被改错，
 * 本测即变红（变异即红，已在独立 worktree 实测）。
 *
 * <p>不启动 Spring 上下文、不触网，纯读资源 + 绑定，故稳定、快速。
 */
class PackagedApplicationYamlTest {

    @Test
    void packagedYamlBindsTemporalTargetToComposeServer() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).as("打包 application.yml 应可被加载").isNotEmpty();

        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        AppProperties properties = binder.bind("app", Bindable.of(AppProperties.class))
                .orElseThrow(() -> new AssertionError("打包 application.yml 未绑定出 app.* 配置"));

        assertThat(properties.getTemporal().getTarget())
                .as("打包 yml 的 app.temporal.target 必须指向 compose 自托管 server（spec §7.3）")
                .isEqualTo("temporal:7233");
        assertThat(properties.getTemporal().getNamespace())
                .as("打包 yml 的 app.temporal.namespace 必须是 default")
                .isEqualTo("default");
    }
}
