package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** ④a 黑名单/领域词全量 grep 结果（命中→打回③）。 */
public record GrepVO(
        long novelId,
        int scanned,
        List<Hit> hits
) {
    /** 命中明细：素材 code、命中字段、命中的词。 */
    public record Hit(String code, String field, String word) {
    }
}
