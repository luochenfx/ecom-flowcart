package io.autocommerce.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ecom-flowcart 聚合装配入口（模块化单体，ADR-0009）。
 *
 * <p>单一 artifact 启动时可切 {@code --role=api|worker|scheduler}（单机合一）；骨架阶段
 * （ticket #18）仅装配 web 空壳，role 启停逻辑与基础设施连接随各 slice（worker-runtime 等）落地。</p>
 */
@SpringBootApplication
public class FlowcartApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowcartApplication.class, args);
    }
}
