package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** LLM 接入配置（平台级）：baseUrl/apiKey 密文/默认模型/超时。明文 key 不落库不出库。 */
@Data
@TableName(value = "llm_providers")
public class LlmProviderDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String baseUrl;
    /** AES-GCM 密文（SecretCipher），格式 base64(iv||ct+tag)。 */
    private String apiKeyCipher;
    /** 默认模型（可空=走节点路由/调用方默认）。 */
    private String model;
    /** 用途（llm/LlmRole：chat=会话 OpenAI 兼容 / embedding=MiniMax 私有向量化），同用途单活。 */
    private String role;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private boolean enabled;
    private String remark;
    private boolean isDeleted;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private OffsetDateTime deleteTime;
}
