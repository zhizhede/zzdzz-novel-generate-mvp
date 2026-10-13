package com.zzdzz.novelgen.model.vo.derive;

/** ⑤约束采样结果（run 建档读数）。 */
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
        String status
) {
}
