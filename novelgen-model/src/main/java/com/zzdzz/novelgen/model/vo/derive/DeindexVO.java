package com.zzdzz.novelgen.model.vo.derive;

/** ③无指向化结果（v3.3：含 grep 自检闭环读数）。 */
public record DeindexVO(
        long novelId,
        int arcLabeled,
        int beatLabeled,
        int worldLabeled,
        int blacklistAdded,
        int domainWordsAdded,
        int rewashRounds,
        int blacklistHitsFinal,
        int domainWordHits
) {
}
