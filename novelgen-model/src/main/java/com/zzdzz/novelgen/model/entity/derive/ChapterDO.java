package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * chapters · 章节（v1.2 §3.12，计划+正文一体；⑥a计划 → ⑥b具体稿 → ⑦正文 → ⑧质检）。
 * 全标量列，MP 标准 CRUD。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chapters")
public class ChapterDO extends DeriveBaseDO {

    /** 所属书 → novels.id（⑥a） */
    private Long novelId;

    /** 章节号（⑥a，与 novel_id 唯一） */
    private Integer chapterNo;

    /** 所属卷号（⑥a-1；单卷书为1） */
    private Integer volumeNo;

    /** 章节名（⑥a title_hint，⑦可定稿；缺省"第N章"） */
    private String title;

    /** 预算字数（⑥a，±10%硬校验） */
    private Integer budget;

    /** 实测字数（⑧脚本回填；⑦生成时写） */
    private Integer charCount;

    /** 本章具体大纲（非无指向，⑥b） */
    private String draftOutline;

    /** 正文（⑦；覆盖式只留终稿） */
    private String content;

    /** 计划节奏（⑥a，挂拍均值派生，允许两位小数） */
    private java.math.BigDecimal pacePlan;

    /** 状态：PLANNED→DRAFTED→QC_PASS→REWORK */
    private String status;

    /** 章注（⑦ <!-- 本章X字，挂拍：seq… -->，QC 用） */
    private String comment;

    /** 章功能槽合同：本章职责、必需拍角色和状态边界。 */
    private String planContract;
}
