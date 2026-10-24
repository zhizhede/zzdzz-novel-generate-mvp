package com.zzdzz.novelgen.model.vo.derive;

/** 源章列表行（content 仅 includeText=true 时给，其余为 null）。 */
public record SourceChapterVO(
        int seq,
        String title,
        Integer charCount,
        String content
) {
}
