package io.autocommerce.app.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code app} 聚合装配层的架构护栏（ADR-0009 / specs/0007 §7.2）。
 *
 * <p>把装配根的依赖方向显式钉死，防止熵增：
 * <ul>
 *   <li>{@link #APP_ONLY_DEPENDS_ON_ALLOWED}：{@code io.autocommerce.app} 只依赖内部模块 +
 *       Spring + Temporal + JDK（不再向别的第三方扩散）；</li>
 *   <li>{@link #APP_IS_ASSEMBLY_LEAF}：{@code app} 是**叶子**——任何模块不得反向依赖它
 *       （装配根被依赖即意味着依赖倒挂）。</li>
 * </ul>
 *
 * <p>扫描范围 = 整个 {@code io.autocommerce}（叶子规则需看到全部模块才能判定"无人依赖 app"）。
 * 本类**不**触碰 core-contracts 的 {@code ArchitectureRulesTest}（三禁环）语义——那是另一张测试类。
 */
@AnalyzeClasses(packages = "io.autocommerce", importOptions = ImportOption.DoNotIncludeTests.class)
class AppArchitectureTest {

    @ArchTest
    static final ArchRule APP_ONLY_DEPENDS_ON_ALLOWED = classes()
            .that().resideInAPackage("io.autocommerce.app..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.app..",
                    "io.autocommerce..",     // 各内部模块（聚合装配）
                    "org.springframework..", // DI / 装配 / REST（扫描到的 api 配置）
                    "io.temporal..",         // Orchestration SDK（WorkflowClient / WorkerFactory）
                    "jakarta..",             // Bean Validation（api 侧传递）
                    "com.fasterxml..",       // jackson（core 模型序列化，传递）
                    "tools.jackson..",       // Jackson 3（Boot 4 HTTP 载荷，api 侧传递）
                    "org.slf4j..",           // 日志
                    "java..")
            .because("app 是聚合装配根：只依赖内部模块 + Spring + Temporal（specs/0007 §7.2）");

    @ArchTest
    static final ArchRule APP_IS_ASSEMBLY_LEAF = noClasses()
            .that().resideOutsideOfPackage("io.autocommerce.app..")
            .should().dependOnClassesThat().resideInAPackage("io.autocommerce.app..")
            .because("app 是叶子装配根：任何模块不得反向依赖它（ADR-0009）")
            .allowEmptyShould(true);
}
