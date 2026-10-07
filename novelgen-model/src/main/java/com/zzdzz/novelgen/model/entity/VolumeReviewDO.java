package com.zzdzz.novelgen.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName(value = "volume_reviews", autoResultMap = true)
public class VolumeReviewDO extends BaseDO {
    private Long novelId;
    private Integer volNo;
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private JsonNode report;
}
