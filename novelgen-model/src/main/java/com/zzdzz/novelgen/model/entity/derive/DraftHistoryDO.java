package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * draft_history · 正文草稿归档（附录 A-2 预留表，V113 启用）。
 *
 * <p>⑧ 打回→⑥b 清正文（clearForRewrite）**之前**，把 content 非空的章逐行落档；
 * 触发条件 = run11 实弹丢稿（REWORK 清稿后 write 失败，前两轮成稿全无留存）。
 * 全标量列（含 text 正文，非 jsonb）——可走 MP 标准 insert，无 ::jsonb 铁律。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("draft_history")
public class DraftHistoryDO extends DeriveBaseDO {

    /** 归档来源 run → run.id */
    private Long runId;

    /** 所属成品书 → novels.id */
    private Long novelId;

    /** 章 → chapters.id */
    private Long chapterId;

    /** 章号 */
    private Integer chapterNo;

    /** 归档序（=归档时 qc_round；同章每次打回各一行） */
    private Integer version;

    /** 归档时 run.qc_round（被第几轮打回） */
    private Integer qcRound;

    /** 被打回的整章正文 */
    private String content;

    /** 归档时字数 */
    private Integer charCount;

    /** 归档原因：REWORK（⑧打回清稿前） */
    private String reason;
}
