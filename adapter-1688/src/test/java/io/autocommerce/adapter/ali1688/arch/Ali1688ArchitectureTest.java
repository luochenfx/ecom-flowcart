package io.autocommerce.adapter.ali1688.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * adapter-1688 模块架构护栏（Testing Decisions §2 / ADR-0009，CI 必跑）。
 *
 * <p>禁环①：插件（Adapter 实现）不反向依赖业务模块——adapter 只依赖
 * io.autocommerce.core（Capability 接口 / 标准模型）+ JDK / Jackson；
 * 依赖 Spring 容器即破坏"Adapter 不依赖 Spring、可独立测试 / fork"（ADR-0007）。
 */
@AnalyzeClasses(packages = "io.autocommerce.adapter.ali1688",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class Ali1688ArchitectureTest {

    @ArchTest
    static final ArchRule ADAPTER_ONLY_DEPENDS_ON_CORE = classes()
            .that().resideInAPackage("io.autocommerce.adapter.ali1688..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.adapter.ali1688..",
                    "io.autocommerce.core..",
                    "java..",
                    "com.fasterxml..")
            .because("禁环①：Adapter 插件不反向依赖业务模块，只认 core 契约（ADR-0009）"
                    + "；零 Spring 依赖（ADR-0007）");
}
