package io.autocommerce.core.contract;

import io.autocommerce.core.contract.AdapterException;

/**
 * 认证能力（可选，specs/0005 §5）。OAuth refresh 等平台专属凭据刷新协议在平台模块内实现；
 * core 只消费"凭据是否可用"（ensureValid），不实现任何平台的刷新协议。
 */
public interface AuthCapability extends Capability {

    Credential refresh(Credential stale) throws AdapterException;
}
