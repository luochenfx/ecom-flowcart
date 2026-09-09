package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 媒体处理请求（MediaProcessor.process 入参）。operation 为机械处理指令标识
 * （去水印/格式转换/尺寸裁剪…），参数随具体处理器；v1 不实现生成式（LOCALIZED 预留）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MediaProcessRequest(String mediaId, String sourceUrl, String operation, String variantLocale) {
}
