package io.autocommerce.core.contract;

import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.ShipmentNotification;

/**
 * 发货回传能力（specs/0005 §2）。供应商物流单号 → 销售平台（顾客可追踪）。
 */
public interface ShipmentCapability extends Capability {

    void notifyShipment(ShipmentNotification notification) throws AdapterException;
}
