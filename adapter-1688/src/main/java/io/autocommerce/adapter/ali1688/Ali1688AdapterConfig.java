package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.CredentialView;

/**
 * 1688 Adapter 装配配置（Composition Root 用；Adapter 自身不依赖 Spring，见 ADR-0007）。
 *
 * <p>{@link Ali1688AdapterProvider} 的无参构造 = 未配置（SPI 发现形态）：
 * {@code OfferFetch} 退化为 #19 子集的 {@code source_ref.url} 直连模式，
 * {@code Purchase} / {@code Auth} 调用即报缺凭据。带配置的构造由 adapter-host 在
 * 拿到某个 channel 的解密凭据后装配。
 *
 * @param credential        1688 凭据；{@code null} = 未配置
 * @param gatewayBaseUrl    网关基址（默认 {@link #DEFAULT_GATEWAY_BASE_URL}；测试/沙箱可覆盖）
 * @param permitsPerSecond  限流速率（默认 {@link #DEFAULT_PERMITS_PER_SECOND}）
 * @param burst             桶容量（默认 {@link #DEFAULT_BURST}）
 */
public record Ali1688AdapterConfig(Ali1688Credential credential, String gatewayBaseUrl,
                                   double permitsPerSecond, int burst) {

    /** 1688 开放平台公网域名（param2 协议，见官方「API 调用说明」）。 */
    public static final String DEFAULT_GATEWAY_BASE_URL = "https://gw.open.1688.com";

    /** 1688 类目 QPS 上限（ADR-0007：1688 类目 ≤10）——超限本地排队，不抛 429。 */
    public static final double DEFAULT_PERMITS_PER_SECOND = 10.0d;

    /** 桶容量：允许的瞬时突发量（等于默认 QPS，即"攒满一秒的量"）。 */
    public static final int DEFAULT_BURST = 10;

    public Ali1688AdapterConfig {
        gatewayBaseUrl = (gatewayBaseUrl == null || gatewayBaseUrl.isBlank())
                ? DEFAULT_GATEWAY_BASE_URL : gatewayBaseUrl;
        if (!(permitsPerSecond > 0)) {
            throw new IllegalArgumentException("permitsPerSecond 必须 > 0，实为 " + permitsPerSecond);
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst 必须 ≥ 1，实为 " + burst);
        }
    }

    /** 未配置形态（SPI 无参发现 / 本地无账号）：无凭据、官方网关、默认限流。 */
    public static Ali1688AdapterConfig unconfigured() {
        return new Ali1688AdapterConfig(null, null, DEFAULT_PERMITS_PER_SECOND, DEFAULT_BURST);
    }

    /** 生产装配：官方网关 + 默认限流。 */
    public static Ali1688AdapterConfig of(Ali1688Credential credential) {
        if (credential == null) {
            throw new IllegalArgumentException("credential 不得为 null；未配置请用 unconfigured()");
        }
        return new Ali1688AdapterConfig(credential, null, DEFAULT_PERMITS_PER_SECOND, DEFAULT_BURST);
    }

    /** Channel 域直接以解密视图装配（§5：Adapter 接口收到的是解密后的内存对象）。 */
    public static Ali1688AdapterConfig of(CredentialView credential) {
        return of(Ali1688Credential.from(credential));
    }

    /** 测试 / 沙箱装配：覆盖网关基址（WireMock 指向本地）。 */
    public static Ali1688AdapterConfig of(Ali1688Credential credential, String gatewayBaseUrl) {
        if (credential == null) {
            throw new IllegalArgumentException("credential 不得为 null；未配置请用 unconfigured()");
        }
        return new Ali1688AdapterConfig(credential, gatewayBaseUrl,
                DEFAULT_PERMITS_PER_SECOND, DEFAULT_BURST);
    }

    /** 覆盖限流参数（返回新实例；平台文档调整时按此调参，不动 core）。 */
    public Ali1688AdapterConfig withRateLimit(double newPermitsPerSecond, int newBurst) {
        return new Ali1688AdapterConfig(credential, gatewayBaseUrl, newPermitsPerSecond, newBurst);
    }

    /** 是否已装配凭据（决定 OfferFetch 走网关还是 {@code source_ref.url} 直连）。 */
    public boolean configured() {
        return credential != null;
    }
}
