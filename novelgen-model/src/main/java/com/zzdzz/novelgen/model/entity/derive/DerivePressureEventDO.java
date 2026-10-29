package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 压力事件：表面事件、隐藏压力、触发者、状态前后和关系/角色变化。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_pressure_event")
public class DerivePressureEventDO extends DeriveBaseDO {
    private Long runId;
    private String eventKey;
    private String eventType;
    private String title;
    private String triggerCharacterKey;
    private String targetCharacterKeys;
    private String organizationKey;
    private String institutionKey;
    private String publicSurface;
    private String hiddenPressure;
    private String beforeState;
    private String afterState;
    private String relationshipChanges;
    private String characterChanges;
    private Integer chapterNo;
    private Integer seq;
    private String status;
}