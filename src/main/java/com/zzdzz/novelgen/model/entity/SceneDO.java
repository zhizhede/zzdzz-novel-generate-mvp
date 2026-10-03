package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** SceneDO。 */
@Data
@TableName(value = "chapter_scenes")
public class SceneDO extends BaseDO {
    private long chapterId;
    private int sceneNo;
    private String goal;
    private String present;
    private String mustReveal;
    private String mustNot;
    private int wordsBudget;
    private String draftText;
    private String gateStatus;
    private int revisionRound;











}
