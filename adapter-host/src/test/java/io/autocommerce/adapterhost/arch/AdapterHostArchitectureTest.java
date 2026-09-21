package io.autocommerce.adapterhost.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * adapter-host 模块架构护栏（specs/0007 §7.1 / ADR-0007 / ADR-0009，CI 必跑）。
 *
 * <p>本模块是 <b>Composition Root</b>：AdapterHost 经 ServiceLoader 装配平台 Adapter 与 AI Step，
 * 但<b>自身绝不依赖任何具体 Adapter</b>——否则编译期就把某平台焊死、破坏插件化（禁环①的反面：
 * 插件化装配点对实现零编译期依赖）。main 依赖白名单 = 本包 + {@code io.autocommerce.core..} + JDK。
 *
 * <p>零 Spring（ADR-0007/0009）：依赖 Spring 容器即破坏"Adapter 可独立测试 / fork"。
 *
 * <p>{@code importOptions = DoNotIncludeTests} 保证断言只作用于 main——测试 classpath 上按需
 * 引入 adapter-1688 / adapter-fake（test scope）不违反本护栏（它们不进生产 artifact）。
 * 形态对齐 {@code adapter-fake} 的 {@code FakeSalesArchitectureTest}。
 */
@AnalyzeClasses(packages = "io.autocommerce.adapterhost",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class AdapterHostArchitectureTest {

    @ArchTest
    static final ArchRule HOST_ONLY_DEPENDS_ON_CORE = classes()
            .that().resideInAPackage("io.autocommerce.adapterhost..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.adapterhost..",
                    "io.autocommerce.core..",
                    "java..")
            .because("specs/0007 §7.1：AdapterHost 只依赖 core-contracts，绝不依赖具体 Adapter"
                    + "（否则编译期焊死平台、破坏插件化）；零 Spring（ADR-0007/0009）");
}
