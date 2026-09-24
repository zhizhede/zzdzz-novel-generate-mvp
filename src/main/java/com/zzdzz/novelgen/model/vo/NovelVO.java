package com.zzdzz.novelgen.model.vo;

/** 作品列表项：含无人续跑读数（derive_config 摘要）与创建时间，书籍管理页用。 */
public record NovelVO(Long id, String title, String description, String approvalMode,
                      String status, int chapterCount, java.time.OffsetDateTime createTime,
                      boolean autoContinue, Integer targetChapters) {
}
