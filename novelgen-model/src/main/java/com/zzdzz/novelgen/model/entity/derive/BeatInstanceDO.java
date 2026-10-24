package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * beat_instance · 拍实例（v1.2 §3.11；⑥a落实例 → ⑥b填实 → ⑦写正文 → ⑧核"每拍恰好一次"）。
 * 全标量列，MP 标准 CRUD。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("beat_instance")
public class BeatInstanceDO extends DeriveBaseDO {

    /** 所属运行 → run.id（⑤） */
    private Long runId;

    /** 全局拍序（⑥a，与 run_id 唯一） */
    private Integer seq;

    /** 拍来源：ARC_SEG=弧段拆拍 / ASSET=拍素材直采 / SYNTHETIC=结构槽补拍 */
    private String source;

    /** 来源素材 → asset_beat.id（source=ASSET 时填） */
    private Long assetId;

    /** 来源父弧 → asset_outline.id（source=ARC_SEG 时填） */
    private Long arcId;

    /** 弧内段序号（source=ARC_SEG 时填） */
    private Integer arcSegNo;

    /** 拍型快照 */
    private String beatType;

    /** 节奏快照 */
    private BigDecimal pacing;

    /** 归属章号（⑥a） */
    private Integer chapterNo;

    /** 归属卷号（⑥a-1；单卷书为1） */
    private Integer volumeNo;

    /** 填实后的具体情节点（⑥b） */
    private String filledText;

    /** 状态：PLANNED→WRITTEN→VERIFIED；SKIPPED=⑥b判定用不上 */
    private String status;

    /** 主叙事层：LOCAL_CASE / MAIN_MYSTERY / CHARACTER_ARC / RELATIONSHIP / WORLD。 */
    private String storyLayer;

    /** 结构功能：CLUE_PLANT / HIGHLIGHT / LOCAL_CLOSURE 等。 */
    private String beatRole;

    /** 出场方式、角色/线程引用、依赖、知识和状态变化。 */
    private String routeContract;
}
