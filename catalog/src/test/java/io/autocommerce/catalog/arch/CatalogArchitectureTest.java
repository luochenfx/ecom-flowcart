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
 * 同时整体依赖面收敛到 core + JDK + Jackson（禁 Spring 容器——业务逻辑模块保持纯 Java，
 * 装配由 composition root 承担）。Postgres 实现落地后唯一放开 {@code org.springframework.jdbc..}
 * （JdbcTemplate，薄 JDBC 层）；Spring 容器 / JPA 仍在禁止之列（见 {@link #PLATFORM_SDK_PACKAGES}）。
 */
@AnalyzeClasses(packages = "io.autocommerce.catalog",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class CatalogArchitectureTest {

    /**
     * 平台 SDK / 模型类包（1688 SDK 若引入必入 com.alibaba.*，此处即拦截）。
     *
     * <p>Spring 收窄为「容器 / 框架」包：catalog 仍禁 Spring 容器与 JPA（spring-context / tx /
     * boot / orm / data-jpa）——业务逻辑模块保持纯 Java、装配由 composition root 承担。唯一放开
     * 的是 {@code org.springframework.jdbc..}（PostgresCatalogStore 的 JdbcTemplate，属薄 JDBC 层
     * 而非容器），故此处不再整体禁 {@code org.springframework..}。
     */
    private static final String[] PLATFORM_SDK_PACKAGES = {
            "com.taobao..",
            "com.alibaba..",
            "com.pinduoduo..",
            "com.aliexpress..",
            "com.shopify..",
            "org.springframework.context..",
            "org.springframework.transaction..",
            "org.springframework.boot..",
            "org.springframework.orm..",
            "org.springframework.stereotype..",
            "jakarta.."
    };

    @ArchTest
    static final ArchRule CATALOG_ONLY_DEPENDS_ON_CORE = classes()
            .that().resideInAPackage("io.autocommerce.catalog..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.catalog..",
                    "io.autocommerce.core..",
                    "java..",
                    "com.fasterxml..",
                    // Postgres 实现（PostgresCatalogStore）需要 JdbcTemplate；白名单仅放开
                    // spring-jdbc 这一薄层——仍禁平台 SDK、禁 JPA、禁 Spring 容器
                    // （spring-context/tx/boot 不在白名单；NO_PLATFORM_SDK_DEPENDENCY 照旧生效）。
                    "org.springframework.jdbc..")
            .because("禁环②：catalog 只认 core 接口，不 import 任何 1688 SDK / 平台模型类"
                    + "（ADR-0009 / #19 AC）；零 Spring 容器依赖，仅 Postgres 实现放开 spring-jdbc");

    @ArchTest
    static final ArchRule NO_PLATFORM_SDK_DEPENDENCY = noClasses()
            .that().resideInAPackage("io.autocommerce.catalog..")
            .should().dependOnClassesThat().resideInAnyPackage(PLATFORM_SDK_PACKAGES)
            .because("禁环②：平台污染 master 的代码级镜像——1688 offer 抓取必须经"
                    + " core OfferFetchCapability（#19 AC）");
}
