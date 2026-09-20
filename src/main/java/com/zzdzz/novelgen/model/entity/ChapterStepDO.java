package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 章节步骤状态行（契约②）：每步每次尝试一行，断点/问责/一屏答案的读模型。 */
@Data
@TableName("chapter_steps")
public class ChapterStepDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long novelId;
    private Long chapterId;
    private Integer chapterNo;
    /** OUTLINE/SCENE/ASSEMBLE/READER/AI_REVIEW/APPROVE/DIGEST */
    private String step;
    /** 场景号或评审轮次；步级行为 NULL */
    private String subKey;
    /** 自愈梯子第几次尝试（从 1 起） */
    private Integer attempt;
    /** RUNNING/DONE/FAILED/INTERRUPTED */
    private String status;
    /** 结构化明细：失败原因原文、verdict、产出摘要（JSON 文本） */
    private String detail;
    private boolean isDeleted;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;
    private java.time.OffsetDateTime deleteTime;
}
