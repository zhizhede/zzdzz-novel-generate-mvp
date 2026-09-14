package com.zzdzz.novelgen.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("embeddings")
public class EmbeddingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceType;
    private Long sourceId;
    private Long novelId;
    private Integer chapterNo;
    private String content;
    private String embedding;
    private boolean isDeleted;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private java.time.OffsetDateTime deleteTime;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public String sourceType() {
        return getSourceType();
    }

    @Deprecated
    public Long sourceId() {
        return getSourceId();
    }

    @Deprecated
    public Long novelId() {
        return getNovelId();
    }

    @Deprecated
    public Integer chapterNo() {
        return getChapterNo();
    }

    @Deprecated
    public String content() {
        return getContent();
    }

    @Deprecated
    public String embedding() {
        return getEmbedding();
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
