package com.zzdzz.novelgen.model.dto.derive;

import java.util.List;

/**
 * ⑤约束采样入参（用户钉死项，v3.2 §5 + v3.3 意图层）。
 * tags 首位=主标签（打分时权重×2）；pace 为五锚点字面量（"0.00"/"0.25"/"0.50"/"0.75"/"1.00"）；
 * seed 不给则服务端随机生成并回传（同 seed ⇒ 同结果）；form/outcome 不给则 seed 抽。
 * intent=题材创作 brief（可空；落 run.param_intent，⑥b/⑦ 注入，不参与⑤过滤）。
 * style=目标文风自由文本（可空；v3.6 方案B，落 run.param_style，⑦ 注入优先于源书文风卡）。
 * intakeOptimize=输入思路 AI 优化开关（默认关；开启须带 intent——⑤ 先 LLM 预审思路，
 * 有更优选择时以优化稿顶替 param_intent，评审明细落 run.intake_review；失败 fail-open 用原思路）。
 */
public record RunCreateDTO(
        int wordsMin,
        int wordsMax,
        List<String> tags,
        String pace,
        Long seed,
        String form,
        String outcome,
        Boolean antiRepro,
        String intent,
        String style,
        Boolean intakeOptimize
) {
}
