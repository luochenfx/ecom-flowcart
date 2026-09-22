package io.autocommerce.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.autocommerce.worker.flow.ContentChainKind;

/**
 * 请求体 {@code chain} 的线上取值（specs/0007 §4.3 / §6.2）：{@code domestic} | {@code cross_border}。
 *
 * <p>本枚举是 REST 层的**线上词汇**，与编排层的 {@link ContentChainKind} 分离——请求方声明链路类型
 * （而非按 {@code channelId} 推导），api 只做「线上字符串 → 编排枚举」的映射（见
 * {@link #toContentChainKind()}），不让 Jackson 的大小写 / 下划线策略侵入业务枚举。
 */
public enum ChainKind {

    /** 国内链。 */
    DOMESTIC("domestic", ContentChainKind.DOMESTIC),

    /** 跨境链。 */
    CROSS_BORDER("cross_border", ContentChainKind.CROSS_BORDER);

    private final String wireValue;
    private final ContentChainKind contentChainKind;

    ChainKind(String wireValue, ContentChainKind contentChainKind) {
        this.wireValue = wireValue;
        this.contentChainKind = contentChainKind;
    }

    /** 映射到编排层链路类型（{@link io.autocommerce.worker.flow.ContentChainKind}）。 */
    public ContentChainKind toContentChainKind() {
        return contentChainKind;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * 线上字符串反解。非法值 → {@link IllegalArgumentException}（Jackson 包成反序列化错误 → HTTP 400）。
     */
    @JsonCreator
    public static ChainKind fromWire(String value) {
        for (ChainKind kind : values()) {
            if (kind.wireValue.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(
                "chain 非法：'" + value + "'（合法值：domestic | cross_border）");
    }
}
