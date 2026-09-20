package io.autocommerce.order.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * order 模块架构护栏（Testing Decisions §2 / ADR-0009，CI 必跑）：
 * <ul>
 *   <li>禁环②：order 只认 core 接口（OrderSyncCapability / PurchaseCapability / ShipmentCapability /
 *       RmaCapability / AddressCapability），不直连平台 SDK / 模型类；</li>
 *   <li>零 Temporal：编排壳（workflow / activity）落 worker-runtime，order 保持纯 Java；</li>
 *   <li>零 Spring：业务逻辑模块不引 Spring（装配归 composition root）。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "io.autocommerce.order",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class OrderArchitectureTest {

    /** 平台 SDK / 模型类包（若被引入即入这些包，此处拦截）。 */
    private static final String[] FORBIDDEN_PACKAGES = {
            "com.taobao..",
            "com.alibaba..",
            "com.pinduoduo..",
            "com.aliexpress..",
            "com.shopify..",
            "org.springframework..",
            "jakarta..",
            "io.temporal.."
    };

    @ArchTest
    static final ArchRule ORDER_ONLY_DEPENDS_ON_CORE = classes()
            .that().resideInAPackage("io.autocommerce.order..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.order..",
                    "io.autocommerce.core..",
                    "java..",
                    "javax.crypto..",
                    "com.fasterxml..")
            .because("禁环②：order 只认 core 接口；零 Temporal / 零 Spring（ADR-0009）");

    @ArchTest
    static final ArchRule NO_PLATFORM_OR_ORCHESTRATION_DEPENDENCY = noClasses()
            .that().resideInAPackage("io.autocommerce.order..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_PACKAGES)
            .because("禁环②：平台污染与编排壳都不许进业务模块——能力经 core 接口，编排落 worker-runtime");
}
