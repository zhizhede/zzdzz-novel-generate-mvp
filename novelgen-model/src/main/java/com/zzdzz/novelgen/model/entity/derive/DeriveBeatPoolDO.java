package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 全局拍池：模型先产出整本书的拍，章节归属由系统装箱后回写 beat_instance。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_beat_pool")
public class DeriveBeatPoolDO extends DeriveBaseDO {
    private Long runId;
    private String beatKey;
    private Integer poolSeq;
    private String beatRole;
    private String source;
    private String goal;
    private String novelty;
    private String stateBefore;
    private String stateAfter;
    private String dependsOn;
    private String focusCharacters;
    private String threadRefs;
    private String requiresKnowledge;
    private String writesKnowledge;
    private Integer revealLevel;
    private Boolean required;
    private Boolean protectedBeat;
    private String status;
    private String contract;
}
