package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 不可变计划快照：revision 是计划版本事实源，graph/admission_report 必须走 XML ::jsonb。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_plan_snapshot")
public class DerivePlanSnapshotDO extends DeriveBaseDO {

    /** 所属运行。 */
    private Long runId;

    /** 计划版本；同 run 内从 1 单调递增。 */
    private Integer revision;

    /** 冻结故事合同版本。 */
    private Integer contractVersion;

    /** 冻结故事合同 hash。 */
    private String contractHash;

    /** DRAFT / ADMITTED / SUPERSEDED。 */
    private String status;

    /** 计划图稳定 JSON 的 SHA-256。 */
    private String planHash;

    /** CompiledStoryPlan 的稳定 JSON。 */
    private String graph;

    /** 计划审计与准入报告 JSON。 */
    private String admissionReport;
}