package io.autocommerce.core.contract;

/**
 * Capability 标记接口（ADR-0007）。平台 Adapter 按需实现能力接口族，可部分实现——
 * 未实现的能力 = core 侧该链路不可用（如纯采购平台 Adapter 不实现 PublishCapability），
 * 不影响 core 其它链路。core 的 workflow activity 依赖能力接口而非平台类。
 */
public interface Capability {
}
