package com.zzdzz.novelgen.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName(value = "volume_reviews", autoResultMap = true)
public class VolumeReviewDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long novelId;
    private Integer volNo;
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private JsonNode report;
    private boolean isDeleted;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private java.time.OffsetDateTime deleteTime;







}
