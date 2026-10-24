package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** asset_beat · 拍素材（v1.2 §3.4，②③产物；全标量列）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_beat")
public class AssetBeatDO extends DeriveBaseDO {

    /** 资产编号 "S-P01"（④） */
    private String code;

    /** 血缘源书 → novels.id（②） */
    private Long srcNovelId;

    /** 抽取稿（②，≤40字拍） */
    private String extractedText;

    /** 标签层"谁做什么发生什么结果"（③） */
    private String labelText;

    /** 功能标签（③） */
    private String funcTag;

    /** 原生父弧 → asset_outline.id（②，供"抽弧连带原生拍链"升级用） */
    private Long parentArcId;
}
