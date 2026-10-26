package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 章节装箱合同：系统在全局拍池之后生成，具体稿不得自行改结构。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_chapter_contract")
public class DeriveChapterContractDO extends DeriveBaseDO {
    private Long runId;
    private Integer chapterNo;
    private Integer volumeNo;
    private String requiredRoles;
    private String targetStateBefore;
    private String targetStateAfter;
    private Integer revealLevel;
    private Integer minBeats;
    private Integer maxBeats;
    private String outlineContract;
    private Integer packVersion;
}
