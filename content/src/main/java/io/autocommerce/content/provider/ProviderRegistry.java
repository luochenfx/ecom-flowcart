package io.autocommerce.content.provider;

import io.autocommerce.core.step.LLMProvider;
import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.ProviderId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LLM provider 装配表（#20 AC-4）：{@code ProviderId → LLMProvider}。
 *
 * <p>v1 只有 {@link OpenAICompatProvider} 一种实现（配置即换后端），装配点（composition root）注册；
 * 未来特殊协议平台写独立 provider 并在此登记，core 与 Step 声明不改（specs/0006 §4）。
 *
 * <p><b>重审触发条件（#43 标注，本 ticket 不动代码）</b>：当前的"注册表"是**为第 2 个 provider
 * 预留**的形态——在只有一张表项时，它比直接持有单个 provider 多出来的只有两件事：重复注册校验、
 * 以及"未注册即失败"的明确报错（不静默回退）。**两类触发点，重审的是不同问题**：
 * <ol>
 *   <li><b>出现第 2 个 LLMProvider</b>——表项形态是否还成立（是否该分裂／换选型结构），
 *       以及"多 provider 之间如何选"（落在 {@link io.autocommerce.core.step.ModelResolver}
 *       还是注册表自身）；</li>
 *   <li><b>出现第 2 条 model_requirement 路由规则</b>——注意单加路由规则**不改本表表项数**
 *       （键是 {@code ProviderId}，映射表在 {@code ModelResolver} 侧），但它让"选型职责归谁"
 *       第一次成为真问题，故与 (1) 同列触发条件（口径对齐 {@code ResolvingLlmGateway}）。</li>
 * </ol>
 * 现在作答就是臆造需求。
 */
public final class ProviderRegistry {

    private final Map<ProviderId, LLMProvider> providers;

    private ProviderRegistry(Map<ProviderId, LLMProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    public static ProviderRegistry of(LLMProvider... providers) {
        Map<ProviderId, LLMProvider> byId = new LinkedHashMap<>();
        for (LLMProvider provider : providers) {
            Objects.requireNonNull(provider, "provider 必填");
            if (byId.put(provider.id(), provider) != null) {
                throw new IllegalArgumentException("provider id 重复注册: " + provider.id().value());
            }
        }
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("provider 注册表不能为空");
        }
        return new ProviderRegistry(byId);
    }

    /** 取 provider；未注册 = 装配错误（不静默回退到别的后端，specs/0006 §4 解析点语义）。 */
    public LLMProvider get(ProviderId id) throws ProviderException {
        LLMProvider provider = providers.get(id);
        if (provider == null) {
            throw new ProviderException("未注册的 provider: " + (id == null ? "null" : id.value())
                    + "（已注册: " + providers.keySet().stream().map(ProviderId::value).toList() + "）");
        }
        return provider;
    }

    public int size() {
        return providers.size();
    }
}
