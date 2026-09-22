package io.autocommerce.api;

/**
 * 请求体语义非法（Bean Validation 触及不到的嵌套必填字段）→ HTTP 400。
 *
 * <p>顶层必填 / 枚举非法由 Bean Validation 直接收口 400；本异常用于 core 记录（{@code source_ref}）的
 * 内部必填字段复核——避免 mapping 期的 {@link IllegalArgumentException} 冒泡成 500。
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
