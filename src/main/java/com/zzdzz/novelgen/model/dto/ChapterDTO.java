package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ChapterDTO。 */
@Data
@TableName(value = "chapters")
public class ChapterDTO {
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
    /** 人工打回意见（流 A）：注入下次章纲提示词，消费后清零。 */
    private String rejectReason;
    private boolean isDeleted;


















}
