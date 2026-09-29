package com.zzdzz.novelgen.model.vo;

/**
 * 采纳指纹草稿入参：fingerprintJson 来自草稿原样回传（后端校验必须是含 baseline 的 JSON 对象）。
 * syncBudgetBand 为真时同时把草稿的章长带写进本书 gate_config（缺省不写）。
 */
public record FingerprintApplyVO(
        String fingerprintJson,
        Integer budgetMin,
        Integer budgetMax,
        Double chapterLengthTolerance,
        Boolean syncBudgetBand) {
}
