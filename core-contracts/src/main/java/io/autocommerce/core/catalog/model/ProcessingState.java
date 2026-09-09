package io.autocommerce.core.catalog.model;

/**
 * 媒体处理状态（schema: MediaAsset.processing_state enum）。
 * 下载/处理/上传由后台任务推进，不在商品模型内。
 */
public enum ProcessingState {
    RAW,
    DOWNLOADED,
    PROCESSED,
    UPLOADED,
    FAILED
}
