package io.autocommerce.worker.publish;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * worker-runtime 铺货链架构护栏（ADR-0002 编排壳的机械约束，与内容链 / 订单链同例）：
 * <ul>
 *   <li><b>workflow 必须确定性</b>——会被 Temporal 重放，任何 I/O / store 读取都会在重放时分叉；</li>
 *   <li><b>activity 不依赖 workflow API</b>——activity 是普通方法，误用 {@code Workflow.*} 会静默失效。</li>
 * </ul>
 */
@AnalyzeClasses(packages = "io.autocommerce.worker.publish",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class PublishRuntimeArchitectureTest {

    @ArchTest
    static final ArchRule WORKFLOW_IMPL_STAYS_DETERMINISTIC = noClasses()
            .that().haveSimpleName("PublishWorkflowImpl")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.io..", "java.net..", "java.nio.file..")
            .because("workflow 会被 Temporal 重放：零 I/O，副作用一律经 activity");

    @ArchTest
    static final ArchRule WORKFLOW_IMPL_TOUCHES_NO_STORE = noClasses()
            .that().haveSimpleName("PublishWorkflowImpl")
            .should().dependOnClassesThat().haveSimpleName("JsonFilePublishStateStore")
            .because("workflow 不读状态库：可重入检查走 activity（inspect），不直连 store");

    @ArchTest
    static final ArchRule ACTIVITY_IMPL_USES_NO_WORKFLOW_API = noClasses()
            .that().haveSimpleName("PublishActivitiesImpl")
            .should().dependOnClassesThat().resideInAnyPackage("io.temporal.workflow..")
            .because("activity 是普通方法：误用 Workflow.* 会静默失效（非法在 activity 上下文之外调用）");
}
