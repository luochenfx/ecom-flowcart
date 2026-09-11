package io.autocommerce.worker.content;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * worker-runtime 架构护栏（#20 自加；ADR-0002 编排壳的机械约束）。
 *
 * <p>为什么这三条值得单独拦：
 * <ul>
 *   <li><b>workflow 必须确定性</b>——它会被 Temporal 重放，任何 I/O / 时钟 / 状态读取都会在重放时
 *       分叉（Temporal 的经典坑）。把"不许碰 I/O 与 CatalogStore"写成规则，比 code review 可靠；</li>
 *   <li><b>activity 不依赖 workflow API</b>——activity 是普通方法，误用 Workflow.* 会静默失效；</li>
 *   <li><b>workflow 不认识具体 Step</b>——ADR-0008：步骤由 SPI 提供、由计划调度，编排层按 stepId
 *       调度即可，认识 {@code content.ai} 里的具体实现就意味着新增 Step 要改编排代码。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "io.autocommerce.worker",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ContentRuntimeArchitectureTest {

    @ArchTest
    static final ArchRule WORKFLOW_IMPL_STAYS_DETERMINISTIC = noClasses()
            .that().haveSimpleName("ContentWorkflowImpl")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.io..", "java.net..", "java.nio.file..", "io.autocommerce.catalog..")
            .because("workflow 会被 Temporal 重放：零 I/O、零 store 访问，副作用一律经 activity");

    @ArchTest
    static final ArchRule WORKFLOW_IMPL_KNOWS_NO_CONCRETE_STEP = noClasses()
            .that().haveSimpleName("ContentWorkflowImpl")
            .should().dependOnClassesThat().resideInAnyPackage("io.autocommerce.content.ai..")
            .because("ADR-0008：编排按 plan 里的 stepId 调度，不绑定具体 Step 实现");

    @ArchTest
    static final ArchRule ACTIVITY_IMPL_USES_NO_WORKFLOW_API = noClasses()
            .that().haveSimpleName("ContentChainActivitiesImpl")
            .should().dependOnClassesThat().resideInAnyPackage("io.temporal.workflow..")
            .because("activity 是普通方法：误用 Workflow.* 会静默失效（非法在 activity 上下文之外调用）");
}
