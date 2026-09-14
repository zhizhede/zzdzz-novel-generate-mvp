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
    private int completionTokens;
    private int totalTokens;
    private int latencyMs;
    private String status;
    private String errorMsg;
    private String reasoningText;
    private String requestJson;
    private String responseJson;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public String node() {
        return getNode();
    }

    @Deprecated
    public Long novelId() {
        return getNovelId();
    }

    @Deprecated
    public Long chapterId() {
        return getChapterId();
    }

    @Deprecated
    public String model() {
        return getModel();
    }

    @Deprecated
    public int promptTokens() {
        return getPromptTokens();
    }

    @Deprecated
    public int completionTokens() {
        return getCompletionTokens();
    }

    @Deprecated
    public int totalTokens() {
        return getTotalTokens();
    }

    @Deprecated
    public int latencyMs() {
        return getLatencyMs();
    }

    @Deprecated
    public String status() {
        return getStatus();
    }

    @Deprecated
    public String errorMsg() {
        return getErrorMsg();
    }

    @Deprecated
    public String reasoningText() {
        return getReasoningText();
    }

    @Deprecated
    public String requestJson() {
        return getRequestJson();
    }

    @Deprecated
    public String responseJson() {
        return getResponseJson();
    }
}
