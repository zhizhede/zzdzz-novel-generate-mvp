package com.zzdzz.novelgen.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName(value = "volume_reviews", autoResultMap = true)
public class VolumeReviewDTO extends BaseDTO {
    private Long novelId;
    private Integer volNo;
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private JsonNode report;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private java.time.OffsetDateTime deleteTime;







}
