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
    /** 场景级时间锚（V42）：本场景处于何时的自由文本；null=未定，消费方回退章级 time_note。 */
    private String timeAnchor;
    private String draftText;
    private String gateStatus;
    private int revisionRound;
}
