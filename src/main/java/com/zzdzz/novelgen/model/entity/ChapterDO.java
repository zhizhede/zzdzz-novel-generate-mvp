package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ChapterDO。 */
@Data
@TableName(value = "chapters")
public class ChapterDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long novelId;
    private int chapterNo;
    private Integer volumeNo;
    private String arc;
    private String title;
    private String pov;
    private String outlineYaml;
    private String fullText;
    private String goal;
    private String hook;
    private String timeNote;
    private String ruleRefs;
    private String foreshadowRefs;
    private int budgetMin;
    private int budgetMax;
    private String status;
    private int round;
    /** 生成时的评审标准快照（JSON：reader 五参数），历史章节为 NULL。 */
    private String reviewConfig;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public long novelId() {
        return getNovelId();
    }

    @Deprecated
    public int chapterNo() {
        return getChapterNo();
    }

    @Deprecated
    public Integer volumeNo() {
        return getVolumeNo();
    }

    @Deprecated
    public String arc() {
        return getArc();
    }

    @Deprecated
    public String title() {
        return getTitle();
    }

    @Deprecated
    public String pov() {
        return getPov();
    }

    @Deprecated
    public String outlineYaml() {
        return getOutlineYaml();
    }

    @Deprecated
    public String fullText() {
        return getFullText();
    }

    @Deprecated
    public String goal() {
        return getGoal();
    }

    @Deprecated
    public String hook() {
        return getHook();
    }

    @Deprecated
    public String timeNote() {
        return getTimeNote();
    }

    @Deprecated
    public String ruleRefs() {
        return getRuleRefs();
    }

    @Deprecated
    public String foreshadowRefs() {
        return getForeshadowRefs();
    }

    @Deprecated
    public int budgetMin() {
        return getBudgetMin();
    }

    @Deprecated
    public int budgetMax() {
        return getBudgetMax();
    }

    @Deprecated
    public String status() {
        return getStatus();
    }

    @Deprecated
    public int round() {
        return getRound();
    }
}
