package com.zzdzz.novelgen.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("generation_tasks")
public class GenerationTaskDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long novelId;
    private Integer fromChapter;
    private Integer toChapter;
    private String status;
    private Integer doneChapters;
    private Integer currentChapter;
    private String lastMessage;
    private Long submittedBy;
    /** CHAPTERS/PLAN/OUTLINE/REVIEW/DIGEST（V20 起落库，旧任务默认 CHAPTERS） */
    private String kind;
    private String payload;
    /** 用户停止请求（流 0）：落库持久，pipeline 在步骤/场景边界消费 */
    private boolean cancelRequested;
    /** 插队暂停请求（④ 批启用） */
    private boolean pauseRequested;
    private boolean isDeleted;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private java.time.OffsetDateTime deleteTime;












}
