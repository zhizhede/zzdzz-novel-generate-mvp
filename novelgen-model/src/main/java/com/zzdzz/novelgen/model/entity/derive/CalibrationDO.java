package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * calibration · 标定记录（v1.2 §3.14；⑤写预测、⑧回填实测，B0 换算表每 10 轮从本表滚动回归）。
 * 全标量列，MP 标准 CRUD。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("calibration")
public class CalibrationDO extends DeriveBaseDO {

    /** 所属运行 → run.id */
    private Long runId;

    /** 本轮节奏锚点（⑤） */
    private BigDecimal pace;

    /** 采用基线 B0（⑤5.1） */
    private Integer b0;

    /** 预测拍数（⑤；取 ceil(Pmax)——规模预测的规划依据） */
    private Integer pPred;

    /** 实测拍数（⑧） */
    private Integer pActual;

    /** 预测字数（⑤，clamp 到 [Nmin,Nmax] 后） */
    private Integer wordsPred;

    /** 实测字数（⑧） */
    private Integer wordsActual;

    /** 章均字数实测（⑧） */
    private Integer chapterAvgActual;
}
