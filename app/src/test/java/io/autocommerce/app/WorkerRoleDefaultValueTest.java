package io.autocommerce.app;

import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「role 缺省值链路」的守门测试（#74 复审 Should⑥）：使命中"实现究竟**怎样**读取角色配置"的回退变红。
 *
 * <h2>为什么需要它（真缺口）</h2>
 * {@link AppContextTest}（{@code app.role=api,worker,scheduler}）与
 * {@code AppContextWithoutWorkerRoleTest}（{@code app.role=api}）<b>都显式提供了 {@code app.role} 键</b>
 * ——于是无论 {@link WorkerRoleCondition} 是"经配置绑定读 {@link AppProperties}（带默认值）"还是
 * "直接读环境属性原始值 {@code getProperty("app.role")}（无默认值）"，两个集成测试都同样通过：
 * <b>键存在</b>时两种实现结果一致，回退<b>不会被任何测试捕获</b>。本测试补的正是这个缺口。
 *
 * <h2>判据（变异即红）</h2>
 * 本测<b>不提供 {@code app.role} 键</b>，仅依赖缺省值链路：{@link AppProperties#getRole()} 的默认值
 * {@code api,worker,scheduler}。正确实现（Binder 绑定 AppProperties）→ 含 worker → 装配 4 个
 * {@code WorkerFactory} bean（断言通过）；若把条件回退为无默认值处理的
 * {@code getProperty("app.role")} → 缺键得 {@code null} → 判"无 worker" → 0 个 bean → <b>本测变红</b>。
 *
 * <p>用 {@link ApplicationContextRunner} 只装配 {@link WorkerConfiguration}（不拉全上下文、不跑
 * config-data，故**不加载**打包 {@code application.yml}，环境里确实无 {@code app.role}）；
 * 四个 bean 标 {@code @Lazy}，故仅断言 bean <b>定义</b>存在，不实例化、不触任何外部服务。
 */
class WorkerRoleDefaultValueTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerConfiguration.class);

    @Test
    void defaultRoleActivatesWorkerBeansWhenNoExplicitRoleKeyPresent() {
        runner.run(context -> assertThat(context.getBeanNamesForType(WorkerFactory.class))
                .as("未提供 app.role 键时须落到默认值 api,worker,scheduler → 装配 4 个 worker bean")
                .containsExactlyInAnyOrder("listingFlowWorkerFactory", "contentWorkerFactory",
                        "publishWorkerFactory", "orderWorkerFactory"));
    }
}
