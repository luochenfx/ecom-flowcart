package io.autocommerce.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JsonFilePublishStateStore —— v1 {@link PublishStateStore} 实现（JSON 文档库，全库单文档）。
 *
 * <p>整个 publish 状态库 = 一份 {@code {root}/publish-states.json}（snake_case 序列化、null 省略、
 * 缩进输出），与 order {@code JsonFileOrderStore} 同形先例——单文档便于人工检视 / demo「落库可见」。
 * 真库实现后续同端口替换（见 {@link PublishStateStore} javadoc）。
 *
 * <p>写路径不做运行时 schema 校验：publish 投影不属 {@code schemas/} 承载的两类（specs/0005 §10.2），
 * 故无契约门；调用方保证写入形态。
 */
public final class JsonFilePublishStateStore implements PublishStateStore {

    /** 与其它文档库根同例的版本锚点。 */
    static final String SCHEMA_VERSION = "0.1.0";

    private final Path file;
    private final ObjectMapper mapper;

    public JsonFilePublishStateStore(Path root) {
        this.file = root.resolve("publish-states.json");
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public Optional<PublishState> get(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            return Optional.empty();
        }
        return read().states().stream()
                .filter(s -> listingId.equals(s.listingId()))
                .findFirst();
    }

    @Override
    public PublishState put(PublishState state) {
        PublishStateDocument doc = read();
        List<PublishState> states = new ArrayList<>(doc.states().stream()
                .filter(s -> !s.listingId().equals(state.listingId()))
                .toList());
        states.add(state);
        write(new PublishStateDocument(SCHEMA_VERSION, List.copyOf(states)));
        return state;
    }

    @Override
    public List<PublishState> list() {
        return List.copyOf(read().states());
    }

    private PublishStateDocument read() {
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        try {
            return mapper.treeToValue(mapper.readTree(file.toFile()), PublishStateDocument.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 publish 状态文档失败: " + file, e);
        }
    }

    /**
     * 原子落盘：先写同目录临时文件再 {@code ATOMIC_MOVE} 覆盖——避免并发读者（activity 写 / 测试或
     * 看板读、或同进程内的轮询）读到半截 JSON。
     */
    private void write(PublishStateDocument doc) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writer().writeValue(temp.toFile(), doc);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 publish 状态文档失败: " + file, e);
        }
    }

    private static PublishStateDocument empty() {
        return new PublishStateDocument(SCHEMA_VERSION, List.of());
    }
}
