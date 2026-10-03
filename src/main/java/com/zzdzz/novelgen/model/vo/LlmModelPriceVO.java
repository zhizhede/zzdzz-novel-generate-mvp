package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.entity.LlmModelPriceDO;
import java.math.BigDecimal;

/** 模型计费价目（元/百万 tokens）：软删除内部字段不出 API。 */
public record LlmModelPriceVO(Long id, String model, String currency,
                               BigDecimal idleInputHit, BigDecimal idleInputMiss, BigDecimal idleOutput,
                               BigDecimal peakInputHit, BigDecimal peakInputMiss, BigDecimal peakOutput,
                               int peakStartHour, int peakEndHour, boolean enabled, String remark) {

    public static LlmModelPriceVO from(LlmModelPriceDO d) {
        return new LlmModelPriceVO(d.getId(), d.getModel(), d.getCurrency(),
                d.getIdleInputHit(), d.getIdleInputMiss(), d.getIdleOutput(),
                d.getPeakInputHit(), d.getPeakInputMiss(), d.getPeakOutput(),
                d.getPeakStartHour(), d.getPeakEndHour(), d.isEnabled(), d.getRemark());
    }
}
