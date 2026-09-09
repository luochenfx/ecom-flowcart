package io.autocommerce.core.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit 架构护栏（Testing Decisions §2 / ADR-0009，CI 必跑）。
 *
 * <p>本测试在 core-contracts 内运行（唯一共享层），当前真实断言两类：
 * <ol>
 *   <li><b>core 纯净</b>：零 Spring / 平台 / 编排框架依赖（core 构建与发布不被平台 churn 阻塞）；</li>
 *   <li><b>core 内部域隔离</b>：catalog/order/message/contract/step 五域只经显式白名单互依
 *       （order→catalog 共享 Provenance；contract→{catalog, order} 消费标准模型），其余交叉 = 违规。</li>
 * </ol>
 *
 * <p><b>三禁环骨架（①②③）</b>：目标包（业务模块 adapter/plugin/projection 实现）随后续 slice
 * 落位；规则现对空包扫描恒通过、不做假红，一旦对应包出现即开始真实拦截。包名锚定后无需改规则
 * 语义，仅当实现包名偏离下述约定时在此同步。
 * ① adapter/AI Step 实现 → 业务模块（插件不反向依赖宿主）
 * ② 业务模块 → 平台 SDK / 模型类（只认 core 接口——平台污染 master 的代码级镜像）
 * ③ 读侧 projection → 写侧内部实现（只读 core 模型 + 投影表）
 */
@AnalyzeClasses(packages = "io.autocommerce.core", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureRulesTest {

    /** 平台 SDK / 编排框架 / 容器包 —— core 一律不得触碰（新增平台先在 CONTEXT/ADR 登记再入此表） */
    private static final String[] FORBIDDEN_CORE_DEPS = {
            "org.springframework..",
            "jakarta..",
            "io.temporal..",
            "com.rabbitmq..",
            "com.taobao..",
            "com.alibaba..",
            "com.pinduoduo..",
            "com.aliexpress..",
            "com.shopify.."
    };

    @ArchTest
    static final ArchRule CORE_FREE_OF_SPRING_AND_PLATFORM_SDK = noClasses()
            .that().resideInAPackage("io.autocommerce.core..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_CORE_DEPS)
            .because("core 唯一共享层零 Spring/平台依赖（ADR-0009）：依赖即构建耦合与平台 churn 传染");

    // ---- core 内部五域隔离（显式白名单表达依赖矩阵，白名单外一律禁止） ----

    @ArchTest
    static final ArchRule CATALOG_IS_SELF_CONTAINED = classes()
            .that().resideInAPackage("io.autocommerce.core.catalog..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.core.catalog..", "java..", "com.fasterxml..")
            .because("catalog 标准模型域自洽，不依赖其它 core 域");

    @ArchTest
    static final ArchRule ORDER_MAY_USE_CATALOG_SHARED_MODEL = classes()
            .that().resideInAPackage("io.autocommerce.core.order..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.core.order..", "io.autocommerce.core.catalog..", "java..", "com.fasterxml..")
            .because("order 域经 catalog.model.Provenance 跨文件共享（order.schema 引用 catalog def），仅此例外");

    @ArchTest
    static final ArchRule MESSAGE_IS_SELF_CONTAINED = classes()
            .that().resideInAPackage("io.autocommerce.core.message..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.core.message..", "java..", "com.fasterxml..")
            .because("envelope/payload 契约域自洽");

    @ArchTest
    static final ArchRule CONTRACT_MAY_CONSUME_STANDARD_MODELS = classes()
            .that().resideInAPackage("io.autocommerce.core.contract..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.core.contract..",
                    "io.autocommerce.core.catalog..", "io.autocommerce.core.order..",
                    "java..", "com.fasterxml..")
            .because("Capability 接口消费标准模型（Listing/SourceRef/Order…），只读不实现");

    @ArchTest
    static final ArchRule STEP_IS_SELF_CONTAINED = classes()
            .that().resideInAPackage("io.autocommerce.core.step..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.core.step..", "java..", "com.fasterxml..")
            .because("AI Step 契约域自洽（字段级读写经 StepContext 桥接，不直依赖模型）");

    // ---- 三禁环骨架（业务模块落位后生效，见类 javadoc） ----

    @ArchTest
    static final ArchRule NO_PLUGIN_TO_BUSINESS = noClasses()
            .that().resideInAnyPackage("..adapter..", "..step.impl..", "..plugin..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..catalog..", "..content..", "..publish..", "..order..", "..projection..")
            .because("禁环① 插件（Adapter/AI Step 实现）不反向依赖业务模块（ADR-0009）——骨架规则")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule NO_BUSINESS_TO_PLATFORM = noClasses()
            .that().resideInAnyPackage(
                    "io.autocommerce.catalog..", "io.autocommerce.content..", "io.autocommerce.publish..",
                    "io.autocommerce.order..", "io.autocommerce.projection..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_CORE_DEPS)
            .because("禁环② 业务模块只认 core 接口，不直连平台 SDK/模型（ADR-0009）——骨架规则")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule NO_PROJECTION_TO_WRITE_IMPL = noClasses()
            .that().resideInAPackage("io.autocommerce.projection..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.catalog..", "io.autocommerce.content..", "io.autocommerce.publish..",
                    "io.autocommerce.order..")
            .because("禁环③ 读侧 projection 只读 core 模型 + 投影表，不依赖写侧内部实现（ADR-0009）——骨架规则")
            .allowEmptyShould(true);
}
