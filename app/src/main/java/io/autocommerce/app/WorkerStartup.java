package io.autocommerce.app;

import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 角色启停组件（specs/0007 §7.2）：应用就绪时解析 {@code app.role}（逗号串），据此启动对应 worker。
 *
 * <h2>启停语义</h2>
 * 各 {@code WorkerFactory} bean 定义受 {@link WorkerRoleCondition} 门控（{@code app.role} 不含
 * {@code worker} 时**不存在** worker bean），且标 {@code @Lazy}——实例化推迟到本组件在
 * {@link ApplicationReadyEvent} 上遍历解析时，届时各 {@code *WorkerFactory.start(...)} 建 worker 并启动。
 * 于是"role 决定启哪些 worker"同时体现在：
 * <ul>
 *   <li><b>装配期</b>：{@link WorkerConfiguration} 整类不装配（{@code api} 角色 → 零 worker）；</li>
 *   <li><b>启动期</b>：本组件解析 role，仅当含 {@code worker} 才解析（= 启动）worker bean 并记录。</li>
 * </ul>
 *
 * <p>解析用 {@link AppRole}（token 化）而非整串相等——{@code app.role} 是逗号串，直接比较会失效。
 */
@Component
public class WorkerStartup {

    private static final Logger log = LoggerFactory.getLogger(WorkerStartup.class);

    private final AppProperties properties;
    private final ObjectProvider<WorkerFactory> workerFactories;

    public WorkerStartup(AppProperties properties, ObjectProvider<WorkerFactory> workerFactories) {
        this.properties = properties;
        this.workerFactories = workerFactories;
    }

    /** 就绪回调：解析 role → 启动（解析）对应 worker。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startWorkers() {
        AppRole role = AppRole.parse(properties.getRole());
        if (!role.hasWorker()) {
            log.info("app.role={} 不含 worker → 不启动任何 Temporal worker（仅 REST / 其它角色）",
                    properties.getRole());
            return;
        }
        List<WorkerFactory> factories = workerFactories.orderedStream().toList();
        log.info("app.role={} 含 worker → 已启动 Temporal worker {} 个"
                        + "（flow / content / publish / order 各 task queue 见对应 *Runtime 常量）",
                properties.getRole(), factories.size());
    }
}
