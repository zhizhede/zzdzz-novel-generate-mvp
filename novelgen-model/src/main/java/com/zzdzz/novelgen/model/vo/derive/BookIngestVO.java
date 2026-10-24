package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** ①取样入库结果。 */
public record BookIngestVO(
        long novelId,
        String title,
        int chapterCount,
        int totalChars,
        boolean renumbered,
        List<String> notes
) {
}
