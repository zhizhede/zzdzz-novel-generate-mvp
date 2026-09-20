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
    public boolean enabled() {
        return isEnabled();
    }

}
