package io.autocommerce.app;

import io.autocommerce.adapterhost.AdapterHost;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SPI 装配根（specs/0007 §7.1）：{@link AdapterHost} bean。
 *
 * <p>{@code AdapterHost.load()} 经 {@code ServiceLoader} 汇总 classpath 上的全部
 * {@code PlatformAdapterProvider}（平台 Adapter）与 {@code AiStepProvider}（AI Step）。
 * 生产 artifact 携带哪些平台**只由 runtime classpath 决定**——新增平台不改任何装配代码（插件化）。
 */
@Configuration
public class AdapterHostConfiguration {

    /** 平台 Adapter 与 AI Step 的汇总入口（specs/0007 §7.1）。 */
    @Bean
    AdapterHost adapterHost() {
        return AdapterHost.load();
    }
}
