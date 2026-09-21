package io.autocommerce.adapter.fake.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * adapter-fake 模块架构护栏（Testing Decisions §2 / ADR-0009，CI 必跑）。
 *
 * <p>禁环①：插件（Adapter 实现）不反向依赖业务模块——测试 Adapter 虽为测试用途，
 * 仍<b>与本仓真实 Adapter 走同一 SPI 路径</b>，故受同一护栏约束：只依赖
 * io.autocommerce.core（Capability 接口 / 标准模型）+ JDK + Jackson；
 * 依赖 Spring 容器即破坏"Adapter 不依赖 Spring、可独立测试 / fork"（ADR-0007）。
 *
 * <p>本模块无 HTTP 端点，不需要 javax..（JDK 自带 JCE）——故白名单不含 javax，
 * 任何新增第三方依赖都应先问一遍是否破了禁环①。形态对齐
 * {@code adapter-1688} 的 {@code Ali1688ArchitectureTest}。
 */
@AnalyzeClasses(packages = "io.autocommerce.adapter.fake",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class FakeSalesArchitectureTest {

    @ArchTest
    static final ArchRule ADAPTER_ONLY_DEPENDS_ON_CORE = classes()
            .that().resideInAPackage("io.autocommerce.adapter.fake..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.adapter.fake..",
                    "io.autocommerce.core..",
                    "java..",
                    "com.fasterxml..")
            .because("禁环①：Adapter 插件不反向依赖业务模块，只认 core 契约（ADR-0009）"
                    + "；零 Spring 依赖（ADR-0007）");
}
