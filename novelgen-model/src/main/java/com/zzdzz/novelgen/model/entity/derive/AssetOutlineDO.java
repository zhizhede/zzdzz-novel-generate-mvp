package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** asset_outline · 弧素材（v1.2 §3.3，②③产物；全标量列）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_outline")
public class AssetOutlineDO extends DeriveBaseDO {

    /** 资产编号 "O-P01"（④全局重编号，跨表前缀注册制 O/S/W…） */
    private String code;

    /** 血缘源书 → novels.id（②） */
    private Long srcNovelId;

    /** 抽取稿：②有指向原文（血缘列，永久保留） */
    private String extractedText;

    /** 标签层因果链（③产物，不含标签头） */
    private String labelText;

    /** 功能标签（③，如 [绑定][护短]） */
    private String funcTag;
}
