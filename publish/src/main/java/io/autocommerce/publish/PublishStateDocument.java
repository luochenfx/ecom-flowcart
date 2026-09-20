package io.autocommerce.publish;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * publish 状态库文档根（{@code {root}/publish-states.json} 的顶层形态）。
 *
 * <p>{@code schema_version} 与其它文档库根同例（{@code 0.1.0}），仅作未来演进的锚点；
 * 本文件<b>不</b>对应 {@code schemas/} 契约（publish 投影不属标准模型 / 消息两类，
 * 见 specs/0005 §10.2 承载边界），故无运行时 schema 校验。
 *
 * @param schemaVersion 文档版本锚点
 * @param states        全部投影行
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PublishStateDocument(String schemaVersion, List<PublishState> states) {
}
