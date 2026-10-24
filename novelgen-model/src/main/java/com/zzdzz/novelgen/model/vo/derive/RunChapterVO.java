package com.zzdzz.novelgen.model.vo.derive;

/** 成品章进度行（content 仅 includeText=true 时给，其余为 null；hasText 恒按库内正文判）。 */
public record RunChapterVO(
        int chapterNo,
        String title,
        Integer budget,
        Integer charCount,
        String status,
        boolean hasText,
        String comment,
        String content
) {
}
