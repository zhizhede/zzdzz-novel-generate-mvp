package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * qc_report · 质检报告（v1.2 §3.13；⑧每轮一行）。
 *
 * <p>jsonb 四列（scriptResult/llmResult/issues/repairLog）禁走 MP save()，
 * 写入走 {@code QcReportMapper.insertFull}（XML 显式 ::jsonb）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("qc_report")
public class QcReportDO extends DeriveBaseDO {

    /** 所属运行 → run.id */
    private Long runId;

    /** 轮次（1 起，与 run_id 唯一） */
    private Integer roundNo;

    /** 脚本硬判结果 jsonb（字数/挂拍/黑名单/反域/浓度/指纹 逐项） */
    private String scriptResult;

    /** LLM 快检结果 jsonb（兑现/节奏/语气/域 四项） */
    private String llmResult;

    /** 判定：PASS / REWORK / FAIL */
    private String verdict;

    /** 问题清单 jsonb */
    private String issues;

    /** 修复记录 jsonb（≤2 轮，只记问题与动作不存全文） */
    private String repairLog;
}
