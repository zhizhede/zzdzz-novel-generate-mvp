package com.zzdzz.novelgen.model.vo;

import java.time.OffsetDateTime;

/**
 * 规划资产行（只读读模型，三层共用一条记录 + level 判别）：
 * OUTLINE＝一本书一行的大纲文档；VOLUME＝一书一卷一行（章行聚合）；CHAPTER＝一章一行（章纲）。
 * 与层级无关的字段留 null，前端按 level 渲染不同列（与文风指纹页同一套「宽读模型 + 前端按来源渲染」的做法）。
 */
public record PlanAssetVO(
        /** OUTLINE / VOLUME / CHAPTER。 */
        String level,
        long novelId,
        String novelTitle,
        /** IMPORTED 手动导入 / DERIVED 系统衍生 / ORIGINAL 系统纯原创。 */
        String sourceType,
        /** 卷纲/章纲行：卷号；章纲行可能为 null（导入章未分卷）。 */
        Integer volumeNo,
        /** 卷纲行的卷名（卷内规划行的 arc）。 */
        String arc,
        /** 章纲行：章号。 */
        Integer chapterNo,
        String chapterTitle,
        String goal,
        String hook,
        String timeNote,
        /** 卷纲行：章数、章号区间、有章纲章数、有正文章数。 */
        Integer chapterCount,
        Integer fromChapter,
        Integer toChapter,
        Integer outlineChapters,
        Integer textChapters,
        /** 大纲/章纲是否已存在（卷纲层＝卷内至少一章有章纲）。 */
        Boolean hasOutline,
        Long outlineChars,
        /** 大纲层＝大纲全文；章纲层＝章纲 YAML。 */
        String outline,
        /** 大纲层：是否仍是样本剧情骨架（未改写），此类大纲不许直接开写。 */
        Boolean skeleton,
        Integer budgetMin,
        Integer budgetMax,
        /** 章纲行：章状态（NEW/OUTLINED/.../FINAL＝导入正文）。 */
        String status,
        Boolean hasText,
        Long textChars,
        /** 章纲行：本章伏笔引用条数。 */
        Integer foreshadowRefs,
        /** 卷纲行：有无卷复盘、复盘摘要、落差条数与其中 major 条数、复盘 JSON 原文。 */
        Boolean hasReview,
        String reviewSummary,
        Integer reviewDrifts,
        Integer reviewMajor,
        String review,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
