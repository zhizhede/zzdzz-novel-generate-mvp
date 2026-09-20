package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** LlmCallLogDO。 */
@Data
@TableName(value = "llm_call_log")
public class LlmCallLogDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String node;
    private Long novelId;
    private Long chapterId;
    private String model;
    private int promptTokens;
    private int cachedTokens;
    private int completionTokens;
    private int totalTokens;
    private int latencyMs;
    private String status;
    private String errorMsg;
    private String reasoningText;
    private String requestJson;
    private String responseJson;
    private boolean isDeleted;
    private java.time.OffsetDateTime createTime;














}
