package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 人物状态账（V39）：一行 = 一个名字在某一章结束时的位置与随身物品。
 * 由 digest 的 world_states 投影而来（见 CharacterStateService.project），不是独立的信息源。
 */
@Data
@TableName(value = "character_states", autoResultMap = true)
public class CharacterStateDO extends BaseDO {
    private Long novelId;
    private Integer chapterNo;
    private String name;
    private String location;
    /** 随身重要物品（字符串数组）；空则不写列。 */
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private JsonNode possessions;
}
