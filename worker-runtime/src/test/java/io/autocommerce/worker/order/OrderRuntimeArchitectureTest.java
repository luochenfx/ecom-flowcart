package io.autocommerce.worker.order;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * worker-runtime 订单链架构护栏（ADR-0002 编排壳的机械约束，与内容链同例）：
 * <ul>
 *   <li><b>workflow 必须确定性</b>——会被 Temporal 重放，任何 I/O / store 读取都会在重放时分叉；</li>
 *   <li><b>activity 不依赖 workflow API</b>——activity 是普通方法，误用 {@code Workflow.*} 会静默失效。</li>
 * </ul>
 *
 * <p>订单链（{@link OrderWorkflowImpl}）与采购单链（{@link PurchaseWorkflowImpl} / 其 activity）同受约束：
 * 采购单自有 workflow 与订单链同级，护栏不得只覆盖其一。
 */
@AnalyzeClasses(packages = "io.autocommerce.worker.order",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class OrderRuntimeArchitectureTest {

    @ArchTest
    static final ArchRule ORDER_WORKFLOW_IMPL_STAYS_DETERMINISTIC = noClasses()
            .that().haveSimpleName("OrderWorkflowImpl")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.io..", "java.net..", "java.nio.file..", "io.autocommerce.order.store..")
            .because("workflow 会被 Temporal 重放：零 I/O、零 store 访问，副作用一律经 activity");

    @ArchTest
    static final ArchRule PURCHASE_WORKFLOW_IMPL_STAYS_DETERMINISTIC = noClasses()
            .that().haveSimpleName("PurchaseWorkflowImpl")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.io..", "java.net..", "java.nio.file..", "io.autocommerce.order.store..")
            .because("采购单 workflow 同样会被 Temporal 重放：零 I/O、零 store 访问，副作用一律经 activity");

    @ArchTest
    static final ArchRule ORDER_ACTIVITY_IMPL_USES_NO_WORKFLOW_API = noClasses()
            .that().haveSimpleName("OrderActivitiesImpl")
            .should().dependOnClassesThat().resideInAnyPackage("io.temporal.workflow..")
            .because("activity 是普通方法：误用 Workflow.* 会静默失效（非法在 activity 上下文之外调用）");

    @ArchTest
    static final ArchRule PURCHASE_ACTIVITY_IMPL_USES_NO_WORKFLOW_API = noClasses()
            .that().haveSimpleName("PurchaseActivitiesImpl")
            .should().dependOnClassesThat().resideInAnyPackage("io.temporal.workflow..")
            .because("activity 是普通方法：误用 Workflow.* 会静默失效（非法在 activity 上下文之外调用）");
}
