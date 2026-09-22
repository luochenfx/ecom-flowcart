package io.autocommerce.api.support;

import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.dto.OfferData;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1688 offer 采集边界的手写 test fake（实现 {@link OfferFetchCapability}）——**不是 mock 框架**
 * （本仓测试哲学，见 {@code ListingFlowWorkflowE2ETest} 类 javadoc）：只替代"外部平台"这一个边界，
 * catalog 侧真实走 {@code CatalogIngestService} → {@code OfferCatalogMapper} → {@code CatalogStore}。
 *
 * <p>可脚本化为三种结果，驱动 ingest 端点的三条错误映射路径（正常 / RETRYABLE / NON_RETRYABLE）。
 */
public final class ScriptedOfferFetch implements OfferFetchCapability {

    /** 采集结果脚本。 */
    public enum Mode {
        /** 正常返回一个最小 offer（1 SKU + 1 图）。 */
        SUCCESS,
        /** 抛 {@code AdapterException(RETRYABLE)} → 期望 HTTP 503。 */
        RETRYABLE,
        /** 抛 {@code AdapterException(NON_RETRYABLE)} → 期望 HTTP 422。 */
        NON_RETRYABLE
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicInteger calls = new AtomicInteger();
    private volatile Mode mode = Mode.SUCCESS;

    public void script(Mode mode) {
        this.mode = mode;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public OfferData fetchOffer(SourceRef ref) {
        calls.incrementAndGet();
        return switch (mode) {
            case RETRYABLE -> throw AdapterException.retryable("THROTTLED", "平台限流（测试用）");
            case NON_RETRYABLE -> throw AdapterException.nonRetryable("INVALID_QUALIFICATION", "资质不足（测试用）");
            case SUCCESS -> minimalOffer(ref);
        };
    }

    private static OfferData minimalOffer(SourceRef ref) {
        return new OfferData(
                ref.externalId(),
                "测试商品标题",
                List.of("https://img.example.com/" + ref.externalId() + "/1.jpg"),
                List.of(new OfferData.OfferSku(
                        "sku-src-1", "spec-1", "颜色:红", List.of(), new Money("9.90", "CNY"))),
                List.of(),
                List.of(),
                MAPPER.createObjectNode());
    }
}
