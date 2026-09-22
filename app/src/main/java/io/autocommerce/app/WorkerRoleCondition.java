package io.autocommerce.app;

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
 * <p>挂载处：{@link WorkerConfiguration} 上（各链 {@code WorkerFactory} bean 的整体开关）。
 */
public class WorkerRoleCondition implements Condition {

    /** 角色配置键。 */
    static final String ROLE_PROPERTY = "app.role";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String role = context.getEnvironment().getProperty(ROLE_PROPERTY);
        return AppRole.parse(role).hasWorker();
    }
}
