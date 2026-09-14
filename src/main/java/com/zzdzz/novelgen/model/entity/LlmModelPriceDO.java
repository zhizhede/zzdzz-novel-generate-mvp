package com.zzdzz.novelgen.model.entity;

import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 模型价目（元/百万 tokens）：空闲/高峰两档，输入拆缓存命中与未命中；高峰时段按行配置。 */
@Data
@TableName(value = "llm_model_prices")
public class LlmModelPriceDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String model;
    private String currency;
    private BigDecimal idleInputHit;
    private BigDecimal idleInputMiss;
    private BigDecimal idleOutput;
    private BigDecimal peakInputHit;
    private BigDecimal peakInputMiss;
    private BigDecimal peakOutput;
    private int peakStartHour;
    private int peakEndHour;
    private boolean enabled;
    private String remark;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public String model() {
        return getModel();
    }

    @Deprecated
    public String currency() {
        return getCurrency();
    }

    @Deprecated
    public BigDecimal idleInputHit() {
        return getIdleInputHit();
    }

    @Deprecated
    public BigDecimal idleInputMiss() {
        return getIdleInputMiss();
    }

    @Deprecated
    public BigDecimal idleOutput() {
        return getIdleOutput();
    }

    @Deprecated
    public BigDecimal peakInputHit() {
        return getPeakInputHit();
    }

    @Deprecated
    public BigDecimal peakInputMiss() {
        return getPeakInputMiss();
    }

    @Deprecated
    public BigDecimal peakOutput() {
        return getPeakOutput();
    }

    @Deprecated
    public int peakStartHour() {
        return getPeakStartHour();
    }

    @Deprecated
    public int peakEndHour() {
        return getPeakEndHour();
    }

    @Deprecated
    public boolean enabled() {
        return isEnabled();
    }

    @Deprecated
    public String remark() {
        return getRemark();
    }
}
