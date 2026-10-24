package com.zzdzz.novelgen.model.vo.derive;

/** ④c域格归纳结果（v3.3：含 CATEGORY_TERM 产出读数）。 */
public record DomainVO(
        long novelId,
        int created,
        int skipped,
        int categoryTermsAdded
) {
}
