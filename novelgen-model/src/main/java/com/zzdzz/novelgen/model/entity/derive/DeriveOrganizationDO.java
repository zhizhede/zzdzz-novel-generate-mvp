package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 组织：公开/私有目标、资源、规则、弱点、压力方式与成员。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_organization")
public class DeriveOrganizationDO extends DeriveBaseDO {
    private Long runId;
    private String orgKey;
    private String orgType;
    private String name;
    private String publicGoal;
    private String privateGoal;
    private String resource;
    private String rule;
    private String weakness;
    private String pressure;
    private String members;
    private String contract;
}