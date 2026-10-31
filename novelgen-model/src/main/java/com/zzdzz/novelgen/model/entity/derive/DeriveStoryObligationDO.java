package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 通用叙事义务：合同、角色、卷与日程的确定性投影。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_story_obligation")
public class DeriveStoryObligationDO extends DeriveBaseDO {

    private Long runId;
    private String obligationKey;
    private Integer chapterNo;
    private String kind;
    private String targetKey;
    private Boolean required;
    private Boolean forbidden;
    private String severity;
    private String sourceContract;
    private String status;
    private String payload;
}
