package io.autocommerce.order.rma;

import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderRma;

import java.util.List;

/**
 * RMA 发现端口（order 模块本地 seam）——"一张销售订单当前挂着哪些售后单"的入口。
 *
 * <p><b>为什么是独立端口而非从 OrderSyncCapability 直接拿</b>：{@code OrderSyncPage} 契约只承载
 * {@code Order + OrderSnapshot}（OrderLine / RMA 等内部履约态由 order 模块落库时生成），而
 * {@code RmaCapability} 只按 {@code platform_rma_id} <b>读状态</b>、不回传 RMA 实体（type / amount /
 * lines / reason）。故 RMA 实体的<b>发现</b>需要一处 seam。
 *
 * <p><b>v1 形态</b>：随订单同步装配一个实现；demo / 测试用 fixture 实现。<b>真实实现</b>（从平台退单 /
 * 纠纷接口或订单原文取回 RMA 实体）归 #23「1688 Adapter 全能力收口」按平台实测回填——此处不拍脑袋
 * 造平台字段映射（specs/0003 §9 / #22 遗留 fog）。
 */
public interface OrderRmaSource {

    /** 返回该订单当前关联的售后实体（可为空列表）。 */
    List<OrderRma> discover(Order order);

    /** 无售后源的默认实现（订单无 RMA 时使用）。 */
    static OrderRmaSource none() {
        return order -> List.of();
    }
}
