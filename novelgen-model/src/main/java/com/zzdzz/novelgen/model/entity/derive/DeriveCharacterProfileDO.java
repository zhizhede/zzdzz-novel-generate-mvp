package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色档案：具体角色的目标、需求、矛盾、决策规则与行为模式。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_character_profile")
public class DeriveCharacterProfileDO extends DeriveBaseDO {
    private Long runId;
    private String characterKey;
    private String displayName;
    private String publicGoal;
    private String privateNeed;
    private String contradiction;
    private String wound;
    private String capability;
    private String limitation;
    private String secret;
    private String decisionRule;
    private String speechPattern;
    private String behaviorPattern;
    private String currentStatus;
    private String arcState;
    private String profile;
}