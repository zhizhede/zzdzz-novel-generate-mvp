package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** SceneDO。 */
@Data
@TableName(value = "chapter_scenes")
public class SceneDO {
    @TableId(type = IdType.AUTO)
    private Long id;
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
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public long chapterId() {
        return getChapterId();
    }

    @Deprecated
    public int sceneNo() {
        return getSceneNo();
    }

    @Deprecated
    public String goal() {
        return getGoal();
    }

    @Deprecated
    public String present() {
        return getPresent();
    }

    @Deprecated
    public String mustReveal() {
        return getMustReveal();
    }

    @Deprecated
    public String mustNot() {
        return getMustNot();
    }

    @Deprecated
    public int wordsBudget() {
        return getWordsBudget();
    }

    @Deprecated
    public String draftText() {
        return getDraftText();
    }

    @Deprecated
    public String gateStatus() {
        return getGateStatus();
    }

    @Deprecated
    public int revisionRound() {
        return getRevisionRound();
    }
}
