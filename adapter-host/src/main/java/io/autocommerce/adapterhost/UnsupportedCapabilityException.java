package io.autocommerce.adapterhost;

import io.autocommerce.core.contract.Capability;

import java.util.Set;

/**
 * 平台已发现，但未实现所请求的能力（specs/0007 §7.1）。
 *
 * <p>{@link AdapterHost#capability(String, Class)} 在平台 {@code capabilities()} 不含该能力类型时抛
 * 本异常——<b>不静默返回 null</b>。能力接口族可部分实现（ADR-0007）：如 1688（货源侧）不实现
 * PublishCapability，向其请求铺货能力必须明确失败，而不是让调用方拿到 null。
 *
 * <p>属 {@link IllegalArgumentException} 家族——调用方给的能力类型对该平台无效。
 */
public final class UnsupportedCapabilityException extends IllegalArgumentException {

    private final String platform;
    private final Class<? extends Capability> capabilityType;

    public UnsupportedCapabilityException(String platform,
                                          Class<? extends Capability> capabilityType,
                                          Set<Class<? extends Capability>> implemented) {
        super("平台 \"" + platform + "\" 未实现能力: " + capabilityType.getName()
                + "（该平台实现清单: " + implemented + "）。");
        this.platform = platform;
        this.capabilityType = capabilityType;
    }

    /** 被请求的平台名。 */
    public String platform() {
        return platform;
    }

    /** 被请求但未实现的能力类型。 */
    public Class<? extends Capability> capabilityType() {
        return capabilityType;
    }
}
