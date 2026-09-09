package io.autocommerce.catalog.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.autocommerce.core.catalog.model.ProductCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JsonFileCatalogStore —— v1 CatalogStore 实现（schema 校验 JSON 文档库，按 SPU 文档一文件）。
 *
 * <p>每个 SPU 一份 {@code {root}/{spuId}.json}，内容 = ProductCatalog 文档（snake_case 序列化、
 * null 省略，与 product-catalog.schema.json 同形）。文件形态便于人工检视 / demo「落库可见」；
 * 真库实现后续同端口替换（见 {@link CatalogStore} javadoc）。
 *
 * <p>写路径不做运行时 schema 校验（校验器仅 test scope，Testing §1 由契约测试承担门）；调用方
 * （ingest 测试 / demo）负责保证写入文档通过 product-catalog schema。
 */
public final class JsonFileCatalogStore implements CatalogStore {

    private final Path root;
    private final ObjectMapper mapper;

    public JsonFileCatalogStore(Path root) {
        this.root = root;
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void put(ProductCatalog product) {
        requireSingleSpu(product);
        Path file = fileFor(spuId(product));
        try {
            Files.createDirectories(root);
            mapper.writer().writeValue(file.toFile(), product);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 catalog 文档失败: " + file, e);
        }
    }

    @Override
    public Optional<ProductCatalog> get(String spuId) {
        Path file = fileFor(spuId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode tree = mapper.readTree(file.toFile());
            return Optional.of(mapper.treeToValue(tree, ProductCatalog.class));
        } catch (IOException e) {
            throw new UncheckedIOException("读取 catalog 文档失败: " + file, e);
        }
    }

    @Override
    public List<String> listSpuIds() {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                ids.add(name.substring(0, name.length() - ".json".length()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列出 catalog 文档失败: " + root, e);
        }
        return ids.stream().sorted().toList();
    }

    private Path fileFor(String spuId) {
        return root.resolve(spuId + ".json");
    }

    private static String spuId(ProductCatalog product) {
        return product.spus().get(0).spuId();
    }

    private static void requireSingleSpu(ProductCatalog product) {
        if (product.spus() == null || product.spus().size() != 1) {
            throw new IllegalArgumentException(
                    "CatalogStore 存储单元 = 单个 SPU 的 product 文档，spus() 必须恰 1 条");
        }
    }
}
