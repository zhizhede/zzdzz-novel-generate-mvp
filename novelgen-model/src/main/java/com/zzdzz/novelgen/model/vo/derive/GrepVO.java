package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** ④a 词表全量 grep 结果（v3.3 按 kind 分口径：BLACKLIST 命中硬判打回③，DOMAIN_WORD 命中报告人工判）。 */
public record GrepVO(
        long novelId,
        int scanned,
        List<Hit> hits,
        int blacklistHits,
        int domainWordHits
) {
    /** 命中明细：素材 code、命中字段、命中的词、词表种类（BLACKLIST/DOMAIN_WORD）。 */
    public record Hit(String code, String field, String word, String kind) {
    }
}
