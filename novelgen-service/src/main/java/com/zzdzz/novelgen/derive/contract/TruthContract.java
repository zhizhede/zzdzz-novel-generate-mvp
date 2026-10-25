package com.zzdzz.novelgen.derive.contract;

import java.util.List;

/** 全书唯一真相合同；真相卡、卷合同、章纲和 P6 共同引用。 */
public record TruthContract(
        String truthId,
        String finalTruth,
        String finalChoice,
        List<String> forbidden,
        List<String> evidenceThreads) {
}