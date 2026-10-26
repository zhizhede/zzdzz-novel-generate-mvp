package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** P6 历史评审：保留每轮 issues/criteria/directive，便于回炉定位与回归。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_gate_review")
public class DeriveGateReviewDO extends DeriveBaseDO {
    private Long runId;
    private Integer round;
    private String verdict;
    private String action;
    private String issues;
    private String criteria;
    private String directive;
    private String repairTarget;
}
