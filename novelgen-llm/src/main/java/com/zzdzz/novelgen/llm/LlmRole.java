package com.zzdzz.novelgen.llm;

/**
 * 接入用途：会话与向量化协议不同，必须分属两行独立配置（llm_providers.role）。
 * 会话 = OpenAI 兼容 /chat/completions（MiniMaxClient）；向量化 = MiniMax 私有 /embeddings
 * （MiniMaxEmbeddingClient，texts/base_resp/vectors，非 OpenAI 兼容）。
 */
public enum LlmRole {

    CHAT("chat"),
    EMBEDDING("embedding");

    private final String wire;

    LlmRole(String wire) {
        this.wire = wire;
    }

    /** 落库字面量（llm_providers.role）。 */
    public String wire() {
        return wire;
    }

    /** 宽松解析：空值回退 CHAT（老调用方/漏传兜底），非法值返回 null 由调用方决定报错口径。 */
    public static LlmRole of(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHAT;
        }
        for (LlmRole role : values()) {
            if (role.wire.equalsIgnoreCase(raw.strip())) {
                return role;
            }
        }
        return null;
    }
}
