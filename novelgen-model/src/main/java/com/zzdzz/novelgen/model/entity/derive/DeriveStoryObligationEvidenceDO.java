package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 叙事义务证据：结构化来源记录，用于 ⑥b/P6/QC 判断义务是否兑现。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_story_obligation_evidence")
public class DeriveStoryObligationEvidenceDO extends DeriveBaseDO {

    private Long runId;
    private String obligationKey;
    private Integer chapterNo;
    private String evidenceKind;
    private String eventKey;
    private Integer beatSeq;
    private String entityKeys;
    private String threadKeys;
    private String fact;
    private String source;
    private Double confidence;
}
