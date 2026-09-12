package com.zzdzz.novelgen.model.entity;

import java.math.BigDecimal;

/** 模型价目（元/百万 tokens）：空闲/高峰两档，输入拆缓存命中与未命中；高峰时段按行配置。 */
public record LlmModelPriceDO(Long id, String model, String currency,
                              BigDecimal idleInputHit, BigDecimal idleInputMiss, BigDecimal idleOutput,
                              BigDecimal peakInputHit, BigDecimal peakInputMiss, BigDecimal peakOutput,
                              int peakStartHour, int peakEndHour, boolean enabled, String remark,
                              boolean isDeleted) {
}
