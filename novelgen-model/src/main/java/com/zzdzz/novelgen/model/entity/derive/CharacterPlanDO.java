package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色叙事计划：功能、高光、冲突和弧光；不保存实时生死状态。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_character_plan")
public class CharacterPlanDO extends DeriveBaseDO {

    private Long runId;
    private Long novelId;
    private String characterKey;
    private String displayNameHint;
    private String functionCode;
    private Integer spotlightVolume;
    private Integer conflictVolume;
    private Integer payoffVolume;
    private String arcBefore;
    private String arcAfter;
    private String contract;
    private String status;
}
