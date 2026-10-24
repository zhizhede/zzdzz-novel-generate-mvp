package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** ⑤约束采样结果（run 建档读数；mappedTags=类别归一化后的池键，v3.5）。 */
public record RunVO(
        long runId,
        long seed,
        int b0,
        int pMin,
        int pMax,
        int wordsPred,
        int kOutline,
        int mBeat,
        String form,
        String outcome,
        String domainName,
        String weldedCode,
        String status,
        List<String> mappedTags
) {
}
