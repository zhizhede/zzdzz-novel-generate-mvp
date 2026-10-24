package com.zzdzz.novelgen.model.vo.derive;

/** ④b 批量打标结果。 */
public record LabelVO(
        long novelId,
        int labeled,
        int replaced
) {
}
