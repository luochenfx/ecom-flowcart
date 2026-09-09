package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 类目挂载（schema: CategoryRef）。多 taxonomy 挂载（SPU 挂来源类目、Listing 挂目标叶子类目），
 * 不建内部统一类目树。label 为展示名缓存，非真源。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CategoryRef(String taxonomy, String value, String label) {
}
