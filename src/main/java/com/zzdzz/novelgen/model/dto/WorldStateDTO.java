package com.zzdzz.novelgen.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName(value = "world_states", autoResultMap = true)
public class WorldStateDTO extends BaseDTO {
    private Long novelId;
    private Integer chapterNo;
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private JsonNode state;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private java.time.OffsetDateTime deleteTime;







}
