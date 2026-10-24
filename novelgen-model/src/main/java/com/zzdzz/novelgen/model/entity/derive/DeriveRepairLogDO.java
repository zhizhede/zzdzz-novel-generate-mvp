package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** P1 修复审计：记录结构化自动修复的 before/after。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_repair_log")
public class DeriveRepairLogDO extends DeriveBaseDO {

    private Long runId;
    private String stage;
    private String repairType;
    private String beforeJson;
    private String afterJson;
    private String reason;
    private String target;
}