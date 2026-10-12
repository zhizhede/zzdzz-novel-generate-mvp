package com.zzdzz.novelgen.model.vo.derive;

/** ②结构抽取结果计数。 */
public record ExtractVO(
        long novelId,
        int outlineCount,
        int beatCount,
        int worldCount,
        int inputChars
) {
}
