package io.autocommerce.catalog.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * catalog 模块架构护栏（Testing Decisions §2 / ADR-0009，CI 必跑；ticket #19 AC：
 * catalog 不 import 任何 1688 SDK / 平台模型类）。
 *
 * <p>禁环②：业务模块只认 core 接口（OfferFetchCapability），不直连平台 SDK / 模型类；
 * 同时整体依赖面收敛到 core + JDK + Jackson（零 Spring——业务逻辑模块保持纯 Java，
 * 装配由 composition root 承担）。
 */
@AnalyzeClasses(packages = "io.autocommerce.catalog",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class CatalogArchitectureTest {

    /** 平台 SDK / 模型类包（1688 SDK 若引入必入 com.alibaba.*，此处即拦截）。 */
    private static final String[] PLATFORM_SDK_PACKAGES = {
            "com.taobao..",
            "com.alibaba..",
            "com.pinduoduo..",
            "com.aliexpress..",
            "com.shopify..",
            "org.springframework..",
            "jakarta.."
    };

    @ArchTest
    static final ArchRule CATALOG_ONLY_DEPENDS_ON_CORE = classes()
            .that().resideInAPackage("io.autocommerce.catalog..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.catalog..",
                    "io.autocommerce.core..",
                    "java..",
                    "com.fasterxml..")
            .because("禁环②：catalog 只认 core 接口，不 import 任何 1688 SDK / 平台模型类"
                    + "（ADR-0009 / #19 AC）；零 Spring 依赖");

    @ArchTest
    static final ArchRule NO_PLATFORM_SDK_DEPENDENCY = noClasses()
            .that().resideInAPackage("io.autocommerce.catalog..")
            .should().dependOnClassesThat().resideInAnyPackage(PLATFORM_SDK_PACKAGES)
            .because("禁环②：平台污染 master 的代码级镜像——1688 offer 抓取必须经"
                    + " core OfferFetchCapability（#19 AC）");
}
