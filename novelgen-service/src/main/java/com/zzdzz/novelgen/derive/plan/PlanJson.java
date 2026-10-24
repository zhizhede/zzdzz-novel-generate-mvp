package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.llm.LlmJson;

/** 故事规划 JSON 合同的校验失败。 */
public final class PlanJson {

    private PlanJson() {
    }

    /** 结构或语义不合规，由 LlmJson 喂回模型重试。 */
    public static class Bad extends LlmJson.Bad {
        public Bad(String message) {
            super(message);
        }
    }
}
