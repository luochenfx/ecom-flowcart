package io.autocommerce.content.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.catalog.model.ListingSku;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.FieldRef;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.core.step.StepDescriptor;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.step.ListingStepContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code price.strategy}（specs/0006 §8）：价格策略 → **Listing SKU 售价集**。
 *
 * <p>model_requirement = **RULE**（纯规则零模型）：成本价 × 加价率，四舍五入到分。
 * 加价规则 v1 是配置项（params.markup_rate）；"可升 LLM"（specs/0006 §8）时 Step 声明改为 LLM
 * 而不改契约——Step 边界本就是字段级读写。
 *
 * <p>失败 = 降级（specs/0006 §8"默认加价率 + degraded"）：配置的 markup_rate 缺失/非法 →
 * 退回 default_markup_rate 计算并返回 DEGRADED（既有产物又不阻断）；连默认都不可用才抛
 * {@link StepExecutionException}（字段保持上游值）。
 */
public final class PriceStrategyStep implements AiStep {

    public static final String ID = ContentPlan.PRICE_STRATEGY;

    /** 售价小数位（金额口径：分）。 */
    private static final int SCALE = 2;

    @Override
    public StepDescriptor descriptor() {
        return new StepDescriptor(
                ID,
                List.of(new FieldRef(ListingStepContext.SPU_SKUS),
                        new FieldRef(ListingStepContext.LISTING_SKU_SET)),
                List.of(new FieldRef(ListingStepContext.LISTING_SKU_SET)),
                ModelRequirement.RULE,
                defaultParams());
    }

    static JsonNode defaultParams() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("markup_rate", "1.80");
        params.put("default_markup_rate", "1.80");
        return params;
    }

    @Override
    public StepResult execute(StepContext context) throws StepExecutionException {
        List<Sku> masterSkus =
                StepValues.typedList(context, new FieldRef(ListingStepContext.SPU_SKUS), Sku.class);
        if (masterSkus.isEmpty()) {
            throw new StepExecutionException("master 无 SKU，价格策略无输入");
        }

        JsonNode params = context.params();
        BigDecimal rate = decimal(params, "markup_rate");
        boolean degraded = false;
        String degradeReason = null;
        if (rate == null || rate.signum() <= 0) {
            BigDecimal fallback = decimal(params, "default_markup_rate");
            if (fallback == null || fallback.signum() <= 0) {
                throw new StepExecutionException("加价率不可用（markup_rate 与 default_markup_rate 均缺失/非法）");
            }
            degraded = true;
            degradeReason = "markup_rate 缺失或非法，已按默认加价率 " + fallback.stripTrailingZeros().toPlainString()
                    + " 计算（默认加价率）";
            rate = fallback;
        }

        Map<String, Boolean> enabledBySku = new LinkedHashMap<>();
        List<ListingSku> existingSkuSet = StepValues.typedList(
                context, new FieldRef(ListingStepContext.LISTING_SKU_SET), ListingSku.class);
        for (ListingSku existing : existingSkuSet) {
            enabledBySku.put(existing.skuId(), existing.enabled());
        }

        List<ListingSku> priced = new ArrayList<>();
        for (Sku sku : masterSkus) {
            Money cost = sku.costPrice();
            if (cost == null || cost.amount() == null || cost.amount().isBlank()) {
                throw new StepExecutionException("SKU 缺 cost_price，无法定价: " + sku.skuId());
            }
            BigDecimal price = new BigDecimal(cost.amount()).multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
            Boolean enabled = enabledBySku.get(sku.skuId());
            priced.add(new ListingSku(sku.skuId(), new Money(price.toPlainString(), cost.currency()),
                    enabled == null || enabled));
        }

        context.write(new FieldRef(ListingStepContext.LISTING_SKU_SET), priced);
        return degraded ? StepResult.degraded(degradeReason) : StepResult.ok();
    }

    private static BigDecimal decimal(JsonNode params, String key) {
        String raw = StepParams.text(params, key, null);
        if (raw == null) {
            Double number = StepParams.number(params, key, null);
            return number == null ? null : BigDecimal.valueOf(number);
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
