package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.CaseOutcome;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 模型价格表（元 / 千 token），来自配置，可随供应商调价修改。
 * 拿不到价格时返回 null 成本，不假装是 0——报告里「成本未知」和「成本为零」是两件事。
 */
@ConfigurationProperties(prefix = "app.eval.pricing")
public record PricingProperties(Price defaults, Map<String, Price> models) {

    public record Price(double input, double output) {
    }

    public PricingProperties {
        if (defaults == null) defaults = new Price(0, 0);
        if (models == null) models = Map.of();
    }

    public Price priceFor(String model) {
        if (model == null) return defaults;
        return models.getOrDefault(model, defaults);
    }

    /** 按 token 数折算金额（元，6 位小数）。 */
    public BigDecimal cost(String model, Integer inputTokens, Integer outputTokens) {
        Price price = priceFor(model);
        if (price.input() == 0 && price.output() == 0) return null;
        if (inputTokens == null && outputTokens == null) return null;
        BigDecimal in = BigDecimal.valueOf(inputTokens == null ? 0 : inputTokens)
                .multiply(BigDecimal.valueOf(price.input()))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(outputTokens == null ? 0 : outputTokens)
                .multiply(BigDecimal.valueOf(price.output()))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        return in.add(out).setScale(6, RoundingMode.HALF_UP);
    }

    public CaseOutcome.Usage usage(String model, Integer inputTokens, Integer outputTokens) {
        return CaseOutcome.Usage.of(inputTokens, outputTokens, cost(model, inputTokens, outputTokens));
    }
}
