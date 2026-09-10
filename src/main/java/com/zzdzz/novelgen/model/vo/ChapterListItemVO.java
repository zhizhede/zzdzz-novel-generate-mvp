package com.zzdzz.novelgen.model.vo;

/** 章列表项（无正文）。 */
public record ChapterListItemVO(Long id, int chapterNo, String title, String status,
                                int round, int budgetMin, int budgetMax) {
}
