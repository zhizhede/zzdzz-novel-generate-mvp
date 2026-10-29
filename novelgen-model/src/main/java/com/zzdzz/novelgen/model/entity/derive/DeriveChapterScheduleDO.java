package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 章级日程：冻结故事合同时的机械投影（每章 mustPlant/mustPayoff/reveal）。
 *
 * <p>只由 SCHEDULE 单元写入；ROUTE/⑥b/P6 只读。合同版本变化即视为过期（SCHEDULE_STALE）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_chapter_schedule")
public class DeriveChapterScheduleDO extends DeriveBaseDO {

    private Long runId;

    private Integer contractVersion;

    private Integer chapterNo;

    private Integer volumeNo;

    private Boolean reveal;

    private String mustPlant;

    private String mustPayoff;
}
