package io.autocommerce.core.contract;

import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.OfferData;

/**
 * 货源采集能力（specs/0005 §2，消费方 = catalog slice）。1688 offer 拉取经本接口——
 * 禁环 ② 禁止业务模块直连平台 SDK，采集必须经 core OfferFetchCapability。
 */
public interface OfferFetchCapability extends Capability {

    OfferData fetchOffer(SourceRef ref) throws AdapterException;
}
