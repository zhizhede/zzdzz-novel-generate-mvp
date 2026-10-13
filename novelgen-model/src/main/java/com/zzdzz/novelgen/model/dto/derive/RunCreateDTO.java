package com.zzdzz.novelgen.model.dto.derive;

import java.util.List;

/**
 * ⑤约束采样入参（用户钉死项，v3.2 §5）。
 * tags 首位=主标签（打分时权重×2）；pace 为五锚点字面量（"0.00"/"0.25"/"0.50"/"0.75"/"1.00"）；
 * seed 不给则服务端随机生成并回传（同 seed ⇒ 同结果）；form/outcome 不给则 seed 抽。
 */
public record RunCreateDTO(
        int wordsMin,
        int wordsMax,
        List<String> tags,
        String pace,
        Long seed,
        String form,
        String outcome,
        Boolean antiRepro
) {
}
