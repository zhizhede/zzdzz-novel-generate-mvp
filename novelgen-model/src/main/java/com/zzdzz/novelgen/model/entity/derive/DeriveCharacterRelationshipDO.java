package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 结构化关系事实：公开/隐藏关系、数值状态、历史与转折。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_character_relationship")
public class DeriveCharacterRelationshipDO extends DeriveBaseDO {
    private Long runId;
    private String fromCharacterKey;
    private String toCharacterKey;
    private String relationshipType;
    private String publicRelation;
    private String hiddenRelation;
    private String initialState;
    private String targetState;
    private String debt;
    private Integer trust;
    private Integer intimacy;
    private Integer conflict;
    private String history;
    private String currentTurn;
    private String payoff;
    private String status;
}