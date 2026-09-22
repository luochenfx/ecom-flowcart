package io.autocommerce.api.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code api} 模块架构护栏（specs/0007 §6.4 依赖方向核查）。
 *
 * <p>把 {@code api} 的允许依赖**显式**钉死，防止入口层熵增：
 * <ul>
 *   <li><b>允许边</b>：{@code api → catalog}（采集入口）/ {@code api → worker-runtime}（编排链 launcher）；
 *       它们经 {@link #API_ONLY_DEPENDS_ON_ALLOWED_MODULES} 的允许清单落位；</li>
 *   <li><b>禁环②</b>：{@code api} 不得 import 任何平台 SDK（{@code com.alibaba..} / {@code com.taobao..} …），
 *       CONTEXT「只为 core 接口与模型」——见 {@link #API_NEVER_TOUCHES_PLATFORM_SDK}；</li>
 *   <li>入口层不引 JPA（存储细节属 catalog / {@code app}）。</li>
 * </ul>
 *
 * <p>三条禁环骨架（{@code ArchitectureRulesTest}，core-contracts）**不被本模块影响**：{@code api}
 * 不在其 target 列表内，且本模块的规则另起一张测试类，未改其语义。
 */
@AnalyzeClasses(packages = "io.autocommerce.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ApiArchitectureTest {

    /** {@code api} 允许依赖的包清单（唯一入口 = 显式白名单）。 */
    @ArchTest
    static final ArchRule API_ONLY_DEPENDS_ON_ALLOWED_MODULES = classes()
            .that().resideInAPackage("io.autocommerce.api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.api..",
                    "io.autocommerce.core..",       // 标准模型 / Capability 契约
                    "io.autocommerce.catalog..",    // 允许边：采集入口
                    "io.autocommerce.worker..",     // 允许边：编排链 launcher + 坐标口径
                    "io.temporal..",                // Orchestration SDK（仅查询端点读状态）
                    "org.springframework..",        // REST / DI（含 Boot 的 Jackson 自动配置定制）
                    "jakarta..",                    // Bean Validation
                    "com.fasterxml..",              // jackson-annotations（@JsonCreator / @JsonValue）
                    "tools.jackson..",              // Jackson 3（HTTP 载荷命名策略，Boot 4 默认）
                    "org.slf4j..",                  // 日志
                    "java..")
            .because("api 是入口层：只依赖 core + catalog + worker-runtime（specs/0007 §6.4）");

    @ArchTest
    static final ArchRule API_NEVER_TOUCHES_PLATFORM_SDK = noClasses()
            .that().resideInAPackage("io.autocommerce.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.alibaba..", "com.taobao..", "com.pinduoduo..",
                    "com.aliexpress..", "com.shopify..")
            .because("禁环②：api 只认 core 接口/模型，不直连平台 SDK（ADR-0009）");

    @ArchTest
    static final ArchRule API_NEVER_TOUCHES_JPA = noClasses()
            .that().resideInAPackage("io.autocommerce.api..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
            .because("api 是入口层，不引 JPA（存储细节属 catalog / app）");

    /** 显式钉住允许边：controller 必须经 catalog / worker-runtime 装配（防"入口直连业务实现"回退）。 */
    @ArchTest
    static final ArchRule CONTROLLER_WIRES_INGEST_AND_FLOW = classes()
            .that().haveSimpleName("FlowController")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.autocommerce.catalog..", "io.autocommerce.worker..")
            .because("api → catalog / api → worker-runtime 是 specs/0007 §6.4 允许的依赖边");
}
