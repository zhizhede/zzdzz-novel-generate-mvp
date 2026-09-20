package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 复盘建议/提案（流 D）：卷级复盘产出的建议条目，人工采纳/忽略；kind=CANON 时承接节点9 canon 提案。 */
@Data
@TableName("retro_proposals")
public class RetroProposalDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long novelId;
    private Integer volNo;
    /** REVIEW 复盘建议 / CANON canon 提案 */
    private String kind;
    private String content;
    /** PROPOSED/ADOPTED/REJECTED */
    private String status;
    private String decisionNote;
    private boolean isDeleted;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private OffsetDateTime deleteTime;
}
