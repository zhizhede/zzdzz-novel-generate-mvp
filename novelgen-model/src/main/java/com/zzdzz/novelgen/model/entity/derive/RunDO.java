package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * run · 装配卡（v1.2 §3.10 聚合根；run 自身即任务行 D9）。
 *
 * <p>jsonb 列（paramTags/domainSnapshot/toleranceTrace/pickedOutlineIds/pickedBeatIds/welded/stepStatus）
 * 禁走 MP save()，写入走 {@code RunMapper.insertFull / updateWeld}（XML 显式 ::jsonb）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run")
public class RunDO extends DeriveBaseDO {

    /** 随机种子（⑤；断点复现三要素之一） */
    private Long seed;

    /** 字数区间下限（⑤·用户钉死） */
    private Integer paramWordsMin;

    /** 字数区间上限（⑤·用户钉死） */
    private Integer paramWordsMax;

    /** 类别 tags jsonb（首位=主标签权重×2） */
    private String paramTags;

    /** 目标节奏 0~1 锚点（0慢1快） */
    private java.math.BigDecimal paramPace;

    /** 产品形态（未给则 seed 抽） */
    private String paramForm;

    /** 结局形状：循环/开放/闭合（⑤seed 抽） */
    private String paramOutcome;

    /** 题材意图 brief（v3.3 意图层；⑥b/⑦ 注入生成提示，⑧ domain 让口；不参与⑤过滤） */
    private String paramIntent;

    /** 目标文风自由文本（v3.6 方案B；⑦ 注入优先于成品书 style_profile，冲突处弃源书习惯） */
    private String paramStyle;

    /** 类别归一化记录 jsonb（v3.5 ⑤前置归一化；param_tags=映射后池键，本列存 raw+映射明细） */
    private String tagsNorm;

    /** 抽中域格 → domain_cell.id */
    private Long domainId;

    /** 域格快照 jsonb（卡片自包含，⑥⑦⑧不回表） */
    private String domainSnapshot;

    /** 每拍字数基线 B0（⑤5.1 锚点换算） */
    private Integer b0;

    /** 预测总拍数区间下限 */
    private Integer pMin;

    /** 预测总拍数区间上限 */
    private Integer pMax;

    /** 抽取弧条数 K */
    private Integer kOutline;

    /** 拍素材补差条数 M */
    private Integer mBeat;

    /** 宽容度轨迹 jsonb（各级降级命中数，v3.2 §5.2） */
    private String toleranceTrace;

    /** 抽中的弧素材 id jsonb 数组 */
    private String pickedOutlineIds;

    /** 抽中的拍素材 id jsonb 数组（M 条，ASSET 直采） */
    private String pickedBeatIds;

    /** 抽中的世界观素材 id */
    private Long pickedWorldId;

    /** 焊接产物 O-Fxx jsonb（K≥2 时，v3.2 §5.3） */
    private String welded;

    /** 生成的成品书 → novels.id（⑥创建后回填） */
    private Long novelId;

    /** 状态机：SAMPLING→PLANNING→DRAFTING→QC→PASSED / REWORK / FAILED */
    private String status;

    /** 已执行质检轮次（⑧，≤2） */
    private Integer qcRound;

    /** L2 步状态 jsonb {"v":1,"steps":{...}} */
    private String stepStatus;

    /** 防复刻开关（V108，默认 FALSE=一致性优先；TRUE=九轮原口径，详见列注释） */
    private Boolean antiRepro;

    /** 输入思路预审记录 jsonb（V114；入参 intakeOptimize 开启时⑤预审，{v,score,holes,changes,source,error?}） */
    private String intakeReview;

    /** 每章字数带校验开关（V115，默认 FALSE=关；关=⑦ 跳过±10% gate、⑧「2每章字数」降报告） */
    private Boolean lenCheck;

    /** 当前唯一 ADMITTED 计划快照 revision（V139；无快照时为空）。 */
    private Integer activePlanRevision;

    /** 类型插件代码；旧 run 为空时兼容读作 MYSTERY。 */
    private String genre;

    /** 通用故事核心契约版本。 */
    private Integer storyKernelVersion;

    /** 类型合同插件契约版本。 */
    private Integer genreContractVersion;
}
