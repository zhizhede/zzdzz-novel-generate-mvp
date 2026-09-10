package com.zzdzz.novelgen.model.vo;

/** 作品列表项。 */
public record NovelVO(Long id, String title, String description, String approvalMode,
                      String status, int chapterCount) {
}
