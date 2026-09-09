package io.autocommerce.core.contract;

import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.RmaStatusView;

/**
 * 售后只读能力（specs/0005 §2）。v1 只读状态供看板/告警；操作类动作（同意退款/申诉）
 * 收敛到平台后台人工，经轮询同步。
 */
public interface RmaCapability extends Capability {

    RmaStatusView fetchRmaStatus(String platformRmaId) throws AdapterException;
}
