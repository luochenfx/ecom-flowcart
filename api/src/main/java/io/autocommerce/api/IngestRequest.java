package io.autocommerce.api;

import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.SourceRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * {@code POST /api/v1/ingest} 请求体（specs/0007 §6.2）。载荷命名 = snake_case（全局策略，见
 * {@link ApiJacksonConfiguration}）。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code source_ref} —— 货源引用（复用 core {@link SourceRef}，不自造副本）；</li>
 *   <li>{@code channel_id} —— 目标渠道账号；</li>
 *   <li>{@code target_category} —— 目标平台叶子类目（复用 core {@link CategoryRef}）；</li>
 *   <li>{@code locales} —— 提交用语言集；</li>
 *   <li>{@code chain} —— 链路类型（国内 / 跨境，**请求方声明**，specs/0007 §4.3）；</li>
 * </ul>
 *
 * <p>顶层必填由 Bean Validation 强制（缺必填 / 枚举非法 → 400）。{@code source_ref} 的**内部必填字段**
 * （platform / external_id / fetched_at）在 core 记录上无校验注解（core 不可改），故由
 * {@link FlowController} 显式复核并同样收口为 400——避免 mapping 期 {@link IllegalArgumentException}
 * 冒泡成 500。
 */
public record IngestRequest(
        @NotNull SourceRef sourceRef,
        @NotBlank String channelId,
        @NotNull CategoryRef targetCategory,
        @NotEmpty List<@NotBlank String> locales,
        @NotNull ChainKind chain) {
}
