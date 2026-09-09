package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 媒体处理结果（MediaProcessor.process 返回）。storageRef 为处理后存储位置
 * （MediaAsset.storage_ref 语义）；variantLocale 表明生成了面向该语言的变体（LOCALIZED 预留）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MediaProcessResult(String mediaId, String storageRef, String variantLocale) {
}
