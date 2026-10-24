package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** asset_world · 世界观素材（v1.2 §3.5，②③产物；全标量列）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_world")
public class AssetWorldDO extends DeriveBaseDO {

    /** 资产编号 "W-P01"（④） */
    private String code;

    /** 血缘源书 → novels.id（②） */
    private Long srcNovelId;

    /** 种类 → world_kind.code（RULE=硬规则 / FACT=世界事实） */
    private String kind;

    /** 抽取稿（②） */
    private String extractedText;

    /** 标签层"条件→后果"（③） */
    private String labelText;
}
