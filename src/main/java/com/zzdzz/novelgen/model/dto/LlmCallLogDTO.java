package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** LlmCallLogDTO。 */
@Data
@TableName(value = "llm_call_log")
public class LlmCallLogDTO extends BaseDTO {
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
    private java.time.OffsetDateTime createTime;














}
