package io.autocommerce.core.contract;

import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseResult;

/**
 * 货源采购能力（specs/0005 §2）。fastCreateOrder/取消/支付（flow=saleproxy）/物流追踪。
 */
public interface PurchaseCapability extends Capability {

    PurchaseResult createPurchase(PurchaseDraft draft) throws AdapterException;

    LogisticsTrace fetchLogistics(String platformPurchaseNo) throws AdapterException;
}
