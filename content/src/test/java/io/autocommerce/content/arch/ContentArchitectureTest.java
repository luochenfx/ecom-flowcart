package io.autocommerce.content.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * content 模块架构护栏（Testing Decisions §2 / ADR-0009 禁环②，CI 必跑；ticket #20）。
 *
 * <p>业务逻辑模块保持纯 Java：零 Spring / 零平台 SDK / **零 Temporal**——Temporal 编排壳
 * （workflow/activity/worker 装配）落 composition root（worker-runtime），与 #19「catalog 纯 Java、
 * 装配归 composition root」同例。
 *
 * <p>额外一条（#20 自加）：内置 Step（{@code content.ai}）只依赖 core 契约 + JDK + Jackson
 * ——ADR-0008"Step 只需懂标准模型字段 + Step 接口 + model_requirement，不碰宿主内部"的机械表达，
 * 保证社区/自研 Step 的接入面与内置 Step 完全一致。
 */
@AnalyzeClasses(packages = "io.autocommerce.content",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ContentArchitectureTest {

    private static final String[] FORBIDDEN = {
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
    static final ArchRule CONTENT_ONLY_DEPENDS_ON_CORE = classes()
            .that().resideInAPackage("io.autocommerce.content..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.content..",
                    "io.autocommerce.core..",
                    "java..",
                    "com.fasterxml..")
            .because("禁环②：业务模块只认 core 接口（ADR-0009 / #20）；零 Spring / 零 Temporal / 零平台 SDK");

    @ArchTest
    static final ArchRule NO_PLATFORM_OR_ORCHESTRATION_FRAMEWORK = noClasses()
            .that().resideInAPackage("io.autocommerce.content..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN)
            .because("业务逻辑模块不直连平台 SDK / Spring / Temporal（编排壳在 worker-runtime）");

    @ArchTest
    static final ArchRule AI_STEPS_DEPEND_ON_CORE_CONTRACT_ONLY = classes()
            .that().resideInAPackage("io.autocommerce.content.ai..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.content.ai..",
                    "io.autocommerce.core..",
                    "java..",
                    "com.fasterxml..")
            .because("Step = 无状态插件：只懂标准模型字段 + Step 接口 + model_requirement（ADR-0008）；"
                    + "本规则若因 content.step 报红，先查 ListingStepContext 的 SPU_* 等常量是否仍为"
                    + "编译期常量——内联失效会让 Step 出现对 content.step 的真实依赖"
                    + "（背景见 content.ai.StepValues javadoc）");
}
