package io.autocommerce.core.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * 契约 Schema 加载器（随 test-jar 分发）。从 classpath:/schemas 加载仓库根 schemas/ 三份
 * JSON Schema（单一事实源经 core-contracts testResources 复制），供双向契约测试与
 * Adapter fixture 门槛复用。
 *
 * <p>跨文件 $ref 处理：order.schema.json 引用 product-catalog.schema.json#/$defs/Provenance，
 * 加载时在内存将引用节点内联展开（内容来自 catalog 单一事实源，不改任何约束），
 * 得到可独立校验的单文档 schema —— 规避校验器跨文件 loader 依赖。
 *
 * <p>format 断言：Draft 2020-12 下 format 默认 annotation-only（与多数实现一致），
 * 结构约束（required/type/enum/pattern）始终生效；fixture 仍用合法值保证未来开启 format 亦可过。
 */
public final class ContractSchemas {

    private static final String CATALOG_RESOURCE = "/schemas/product-catalog.schema.json";
    private static final String ORDER_RESOURCE = "/schemas/order.schema.json";
    private static final String MESSAGE_RESOURCE = "/schemas/message.schema.json";
    private static final String CATALOG_SCHEMA_REF_PREFIX = "product-catalog.schema.json#";

    private static final ObjectMapper MAPPER = ContractObjectMapper.create();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** 事件 type → message.schema.json $defs payload 名（repo 即 registry 的 Java 侧镜像） */
    public static final Map<String, String> EVENT_TYPE_TO_PAYLOAD_DEF = Map.of(
            "order.paid", "OrderPaidPayload",
            "listing.published", "ListingPublishedPayload",
            "listing.ambiguous", "ListingAmbiguousPayload",
            "purchase.shipped", "PurchaseShippedPayload",
            "rma.closed", "RmaClosedPayload",
            "sys.workflow.failed", "SysWorkflowFailedPayload",
            "sys.message.dead_lettered", "SysMessageDeadLetteredPayload");

    private ContractSchemas() {
    }

    /** product-catalog.schema.json（根对象 = ProductCatalog 文档） */
    public static JsonSchema productCatalog() {
        return FACTORY.getSchema(readTree(CATALOG_RESOURCE));
    }

    /** order.schema.json（跨文件 Provenance 引用已内联展开） */
    public static JsonSchema order() {
        JsonNode catalog = readTree(CATALOG_RESOURCE);
        JsonNode order = readTree(ORDER_RESOURCE);
        return FACTORY.getSchema(inlineCatalogRefs((ObjectNode) order, catalog));
    }

    /** message.schema.json（根对象 = MessageDocument） */
    public static JsonSchema message() {
        return FACTORY.getSchema(readTree(MESSAGE_RESOURCE));
    }

    /**
     * 按事件 type 取 payload def schema（如 order.paid → $defs/OrderPaidPayload）。
     * 供 Envelope.payload 运行时按 type 校验（消息消费层与契约测试共用）。
     */
    public static JsonSchema payloadFor(String eventType) {
        String defName = EVENT_TYPE_TO_PAYLOAD_DEF.get(eventType);
        if (defName == null) {
            throw new IllegalArgumentException("未知事件 type: " + eventType);
        }
        JsonNode message = readTree(MESSAGE_RESOURCE);
        JsonNode def = message.at("/$defs/" + defName);
        if (def.isMissingNode()) {
            throw new IllegalStateException("message.schema.json 缺少 $defs/" + defName);
        }
        return FACTORY.getSchema(def);
    }

    /** 递归展开 order schema 中对 catalog schema 的 $ref（当前仅 Provenance def 一处）。 */
    private static ObjectNode inlineCatalogRefs(ObjectNode root, JsonNode catalog) {
        inline(root, catalog);
        return root;
    }

    private static void inline(JsonNode node, JsonNode catalog) {
        if (node instanceof ObjectNode obj) {
            JsonNode ref = obj.get("$ref");
            if (ref != null && ref.isTextual()
                    && ref.asText().startsWith(CATALOG_SCHEMA_REF_PREFIX)) {
                String pointer = ref.asText().substring(CATALOG_SCHEMA_REF_PREFIX.length());
                JsonNode target = catalog.at(pointer);
                if (target.isMissingNode()) {
                    throw new IllegalStateException("catalog schema 无引用目标: " + ref.asText());
                }
                // 用目标 def 内容替换整个 $ref 节点（约束不变，仅消除跨文件引用）
                obj.removeAll();
                obj.setAll((ObjectNode) target.deepCopy());
                return;
            }
            Iterator<String> fields = obj.fieldNames();
            while (fields.hasNext()) {
                inline(obj.get(fields.next()), catalog);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                inline(child, catalog);
            }
        }
    }

    private static JsonNode readTree(String resource) {
        try (InputStream in = ContractSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺失: " + resource
                        + "（schemas/ 由 core-contracts testResources 复制，需先构建）");
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 schema 失败: " + resource, e);
        }
    }
}
