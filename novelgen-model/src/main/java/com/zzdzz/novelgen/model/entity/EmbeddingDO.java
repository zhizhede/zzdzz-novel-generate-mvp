package com.zzdzz.novelgen.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("embeddings")
public class EmbeddingDO extends BaseDO {
    private String sourceType;
    private Long sourceId;
    private Long novelId;
    private Integer chapterNo;
    private String content;
    private String embedding;
}
