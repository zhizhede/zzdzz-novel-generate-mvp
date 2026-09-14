package com.zzdzz.novelgen.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("generation_tasks")
public class GenerationTaskDO {
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
    private boolean isDeleted;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private java.time.OffsetDateTime deleteTime;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public Long novelId() {
        return getNovelId();
    }

    @Deprecated
    public Integer fromChapter() {
        return getFromChapter();
    }

    @Deprecated
    public Integer toChapter() {
        return getToChapter();
    }

    @Deprecated
    public String status() {
        return getStatus();
    }

    @Deprecated
    public Integer doneChapters() {
        return getDoneChapters();
    }

    @Deprecated
    public Integer currentChapter() {
        return getCurrentChapter();
    }

    @Deprecated
    public String lastMessage() {
        return getLastMessage();
    }

    @Deprecated
    public Long submittedBy() {
        return getSubmittedBy();
    }

    @Deprecated
    public java.time.OffsetDateTime createTime() {
        return getCreateTime();
    }

    @Deprecated
    public java.time.OffsetDateTime updateTime() {
        return getUpdateTime();
    }

    @Deprecated
    public java.time.OffsetDateTime deleteTime() {
        return getDeleteTime();
    }
}
