package io.autocommerce.core.message;

/**
 * 首批事件类型常量（schema: message.schema.json 事件目录，ADR-0006）。
 * 命名 = &lt;domain&gt;.&lt;entity&gt;.&lt;past-tense&gt;，全事件化无命令态；breaking 变更 → 新 type（旧冻结）。
 * 新增事件须先改 schemas/message.schema.json（repo 即 registry）再补常量。
 */
public final class EventTypes {

    private EventTypes() {
    }

    /** 销售订单买家已付款 */
    public static final String ORDER_PAID = "order.paid";
    /** 铺货成功（PUBLISHED 终态广播，回填 platform_item_id） */
    public static final String LISTING_PUBLISHED = "listing.published";
    /** 铺货超时歧义挂起（等 reconcile/人工，供看板 HITL） */
    public static final String LISTING_AMBIGUOUS = "listing.ambiguous";
    /** 采购单供应商已发货（tracking 细节回读 PurchaseOrder） */
    public static final String PURCHASE_SHIPPED = "purchase.shipped";
    /** 售后单完结（outcome 摘要） */
    public static final String RMA_CLOSED = "rma.closed";
    /** Lifecycle：workflow failed 终态广播（告警/看板） */
    public static final String SYS_WORKFLOW_FAILED = "sys.workflow.failed";
    /** Lifecycle：DLQ 落库时的广播信号（告警/看板；重放素材与信号两层分离） */
    public static final String SYS_MESSAGE_DEAD_LETTERED = "sys.message.dead_lettered";
}
