package io.autocommerce.app;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * "启用 worker" 的条件（specs/0007 §7.2）：当且仅当 {@code app.role} 含 {@code worker} 时成立。
 *
 * <p><b>为什么不用 {@code @ConditionalOnProperty}</b>：{@code app.role} 是逗号串
 * （{@code api,worker,scheduler}），而 {@code @ConditionalOnProperty(havingValue=...)} 是整串相等
 * 语义——直接套用会恒不成立，导致 worker 永不启动（且可能测试仍绿）。本条件经 {@link AppRole}
 * 做 token 化解析，对逗号串正确（#74 陷阱 3）。
 *
 * <p><b>取值口径（单一事实源）</b>：本条件经 {@link Binder} 把 {@code app.*} 绑定成 {@link AppProperties}
 * 后取 {@code role}，与 {@link WorkerStartup} 消费的是<b>同一个绑定链路</b>——故"缺省时启不启 worker"
 * 只有一个真相源（{@link AppProperties#getRole()} 的默认值 + yml / 环境变量 / 命令行覆盖）。
 * 若直接读环境属性原始值（{@code getProperty("app.role")}），键缺失时得 {@code null} → 判"无 worker"，
 * 而 {@link AppProperties} 默认值为 {@code api,worker,scheduler} → 判"有 worker"，二者自相矛盾（审查 Nit②）。
 *
 * <p>挂载处：{@link WorkerConfiguration} / {@link WorkerServiceConfiguration} 上（worker 侧装配的整体开关）。
 */
public class WorkerRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        AppProperties properties = Binder.get(context.getEnvironment())
                .bind("app", Bindable.of(AppProperties.class))
                .orElseGet(AppProperties::new);
        return AppRole.parse(properties.getRole()).hasWorker();
    }
}
