package com.zzdzz.novelgen.model.vo.derive;

import java.time.OffsetDateTime;

/** 源书列表行（createTime 供列表日期筛选/排序与前端展示，JSON 键保持 createTime）。 */
public record BookVO(
        long id,
        String title,
        String sourceType,
        String author,
        String platform,
        Integer chapterCount,
        Integer charCount,
        OffsetDateTime createTime
) {
}
