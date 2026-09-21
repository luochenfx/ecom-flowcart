package io.autocommerce.catalog.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.autocommerce.core.catalog.model.ProductCatalog;

/**
 * ProductCatalog 文档序列化的单一事实源（catalog 各 CatalogStore 实现共用）。
 *
 * <p>共享配置：snake_case 命名、null 省略——与 {@code product-catalog.schema.json} 同形。
 * 这是 {@link JsonFileCatalogStore}（文件，带缩进便于人工检视）与 {@link PostgresCatalogStore}
 * （jsonb，无需缩进）两实现文档形态一致的保证。
 */
final class CatalogDocJson {

    private CatalogDocJson() {
    }

    /** 文档序列化配置：snake_case + null 省略。 */
    static ObjectMapper newMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    }

    /** 文件实现用：在上述配置上开启缩进输出（便于人工检视 / demo「落库可见」）。 */
    static ObjectMapper newIndentedMapper() {
        return newMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** 序列化为 JSON 字符串。 */
    static String write(ObjectMapper mapper, ProductCatalog product) {
        try {
            return mapper.writeValueAsString(product);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 catalog 文档失败: "
                    + product.spus().getFirst().spuId(), e);
        }
    }

    /** 从 JSON 字符串反序列化为 ProductCatalog。 */
    static ProductCatalog read(ObjectMapper mapper, String json) {
        try {
            JsonNode tree = mapper.readTree(json);
            return mapper.treeToValue(tree, ProductCatalog.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 catalog 文档失败", e);
        }
    }
}
