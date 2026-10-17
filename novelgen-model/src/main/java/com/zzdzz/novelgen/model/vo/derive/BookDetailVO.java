package com.zzdzz.novelgen.model.vo.derive;

import java.time.OffsetDateTime;

/**
 * 书详情（元数据 + 素材处理进度读数）：
 * 源章数来自 source_chapter；弧/拍/世界计数按血缘 src_novel_id；标签数来自 label_index；
 * outlineLabeledCount>0 视为③已跑；hasStyleProfile 读 story_settings.style_profile 键存在性。
 */
public record BookDetailVO(
        long id,
        String title,
        String sourceType,
        String author,
        String platform,
        String summary,
        String categoryTags,
        Integer chapterCount,
        Integer charCount,
        Long runId,
        int sourceChapterCount,
        int outlineCount,
        int outlineLabeledCount,
        int beatCount,
        int worldCount,
        int labelCount,
        boolean hasStyleProfile,
        OffsetDateTime createTime,
        OffsetDateTime updateTime
) {
}
