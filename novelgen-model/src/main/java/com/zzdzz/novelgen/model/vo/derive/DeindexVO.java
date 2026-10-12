package com.zzdzz.novelgen.model.vo.derive;

/** ③无指向化结果。 */
public record DeindexVO(
        long novelId,
        int arcLabeled,
        int beatLabeled,
        int worldLabeled,
        int blacklistAdded,
        int domainWordsAdded
) {
}
