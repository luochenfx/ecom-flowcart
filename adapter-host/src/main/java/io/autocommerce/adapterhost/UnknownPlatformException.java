package io.autocommerce.adapterhost;

/**
 * 请求了 classpath 上未被发现的平台（specs/0007 §7.1）。
 *
 * <p>{@link AdapterHost#capability(String, Class)} 在 platform 不在
 * {@link AdapterHost#platforms()} 内时抛本异常——<b>不静默返回 null</b>。
 * 这是"未实现即明确失败"契约（本票 AC）：链路取不到平台能力时必须立刻暴露，
 * 由编排层决定失败 / 退避，而不是把 null 传下去在更远处 NPE。
 *
 * <p>属 {@link IllegalArgumentException} 家族——调用方给的是无效实参（未知平台名）。
 */
public final class UnknownPlatformException extends IllegalArgumentException {

    private final String platform;

    public UnknownPlatformException(String platform, java.util.Set<String> knownPlatforms) {
        super("adapter-host 未发现平台: \"" + platform + "\"（已发现: " + knownPlatforms
                + "）。请确认对应 Adapter 模块在 classpath 上（runtime/test scope 均可见）。");
        this.platform = platform;
    }

    /** 请求的未知平台名。 */
    public String platform() {
        return platform;
    }
}
