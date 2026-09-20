package io.autocommerce.publish;

import java.util.List;
import java.util.Optional;

/**
 * publish 域状态库端口（#21 决议；沿用 order {@code OrderStore} 范式：端口 + JSON 文档库）。
 *
 * <p>存储单元 = 一行 {@link PublishState}（对 specs/0001 §8 的 {@code execution_projection}
 * {@code type='listing'} 行）。v1 不落地真库表——{@code projection/} 模块 v1 为空，故此处以
 * {@code (listingId) → PublishState} 的 upsert 承载投影语义；真库（Postgres/JPA）表结构属设计期
 * 未决区，届时以同一端口换实现，消费方（workflow / activity）不感知。
 *
 * <p><b>幂等写入</b>：{@link #put} 按 {@code listingId} 覆盖式 upsert（同一 Listing 一行）。
 * 调用方（{@code PublishService}）负责保证同一已落库事实重放时写回<b>同一</b>事实时间
 * （保 {@code envelope.id} 幂等锚稳定）。
 */
public interface PublishStateStore {

    /** 按 listingId 取回投影行；不存在返回 empty。 */
    Optional<PublishState> get(String listingId);

    /**
     * 覆盖式写入一行（同 listingId 覆盖，不存在则新增）。
     *
     * @return 写回的行（即入参，便于调用方复用其事实时间）
     */
    PublishState put(PublishState state);

    /** 列出全部投影行（demo / 看板 seed / 契约检视用）。 */
    List<PublishState> list();
}
