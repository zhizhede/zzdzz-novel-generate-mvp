package com.zzdzz.novelgen.model.vo.derive;

/** 源书列表行。 */
public record BookVO(
        long id,
        String title,
        String sourceType,
        String author,
        String platform,
        Integer chapterCount,
        Integer charCount
) {
}
