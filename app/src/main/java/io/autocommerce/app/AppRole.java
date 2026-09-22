package io.autocommerce.app;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 运行时角色集合（ADR-0009 §5）：解析 {@code app.role} 逗号串（如 {@code api,worker,scheduler}）。
 *
 * <h2>为什么需要它（而非 {@code @ConditionalOnProperty}）</h2>
 * {@code app.role} 是<b>逗号串</b>。{@code @ConditionalOnProperty(name="app.role", havingValue="worker")}
 * 是<b>整串相等</b>语义——把它直接套在逗号串上，条件恒不成立（worker 永不启动，且可能测试仍绿）。
 * 故以本类做 token 化解析，供 {@link WorkerRoleCondition} 判定"是否含 worker"。
 *
 * <p>纯 Java、无 Spring 依赖，便于单测。
 */
public final class AppRole {

    /** 对外 REST 角色（REST 载体）。 */
    public static final String API = "api";

    /** worker 角色（启动 Temporal worker）。 */
    public static final String WORKER = "worker";

    /** 调度器角色（定时触发；v1 预留）。 */
    public static final String SCHEDULER = "scheduler";

    private final Set<String> roles;

    private AppRole(Set<String> roles) {
        this.roles = roles;
    }

    /** 解析逗号串；忽略空白项与前后空格，重复项去重。null / 空串 → 空集合。 */
    public static AppRole parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return new AppRole(Set.of());
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String role = token.trim();
            if (!role.isEmpty()) {
                parsed.add(role);
            }
        }
        return new AppRole(Set.copyOf(parsed));
    }

    /** 是否含指定角色。 */
    public boolean has(String role) {
        return roles.contains(Objects.requireNonNull(role, "role 必填"));
    }

    /** 是否含 worker 角色（决定是否启动 Temporal worker）。 */
    public boolean hasWorker() {
        return has(WORKER);
    }

    /** 已解析的角色集合（不可变）。 */
    public Set<String> roles() {
        return roles;
    }

    @Override
    public String toString() {
        return roles.toString();
    }
}
