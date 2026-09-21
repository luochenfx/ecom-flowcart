package io.autocommerce.worker.flow;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 编排链架构护栏（ADR-0002 / specs/0007 §4.4 的机械约束，与内容链 / 铺货链同例）：
 * <ul>
 *   <li><b>workflow 必须确定性</b>——会被 Temporal 重放，任何 I/O / store 读取都会在重放时分叉
 *       （装配与就绪断言都在 activity 侧）；</li>
 *   <li><b>activity 不依赖 workflow API</b>——activity 是普通方法，误用 {@code Workflow.*} 会静默失效；</li>
 *   <li><b>activity 绝不调 launcher</b>——specs/0007 §4.4 明令：子链必须经
 *       {@code Workflow.newChildWorkflowStub}，禁止在 activity 内调
 *       {@code ContentWorkflowLauncher} / {@code PublishWorkflowLauncher}（Temporal 官方反模式：
 *       activity 阻塞、重试时重复启动子链、子链不随父链取消传播）。此规则把该禁令写成机械拦截。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "io.autocommerce.worker.flow",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ListingFlowRuntimeArchitectureTest {

    @ArchTest
    static final ArchRule WORKFLOW_IMPL_STAYS_DETERMINISTIC = noClasses()
            .that().haveSimpleName("ListingFlowWorkflowImpl")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.io..", "java.net..", "java.nio.file..",
                    "io.autocommerce.catalog..", "io.autocommerce.content.listing..")
            .because("workflow 会被 Temporal 重放：零 I/O、零 store 访问（装配/就绪断言都在 activity），"
                    + "副作用一律经 activity 与 child workflow");

    @ArchTest
    static final ArchRule ACTIVITY_IMPL_USES_NO_WORKFLOW_API = noClasses()
            .that().haveSimpleName("ListingFlowActivitiesImpl")
            .should().dependOnClassesThat().resideInAnyPackage("io.temporal.workflow..")
            .because("activity 是普通方法：误用 Workflow.* 会静默失效（非法在 activity 上下文之外调用）");

    @ArchTest
    static final ArchRule ACTIVITIES_NEVER_CALL_LAUNCHERS = noClasses()
            .that().resideInAPackage("io.autocommerce.worker.flow..")
            .and().haveSimpleNameEndingWith("ActivitiesImpl")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("WorkflowLauncher")
            .because("Temporal 官方反模式：activity 内调 launcher 会阻塞、重试时重复启动子链、"
                    + "不随父链取消传播；子链必须经 Workflow.newChildWorkflowStub（specs/0007 §4.4）");
}
