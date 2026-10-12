package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * label_index · 标签索引（v1.2 §3.7，④b 产物；**标签单一事实源**——素材表/章节表不再重复存标签）。
 *
 * <p>jsonb 列（cat/arcFit）禁走 MP save()，一律 {@code LabelIndexMapper.insertFull}。
 * pacing = 0~1 锚点制 numeric(3,2)，0慢1快；LLM 行仅锚点（DB CHECK 强制），DERIVED/MANUAL 允许两位小数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("label_index")
public class LabelIndexDO extends DeriveBaseDO {

    /** 条目类型：OUTLINE / BEAT / WORLD */
    private String entryType;

    /** 条目 id（对应三张素材表 id；与 entry_type 组复合唯一，多态无外键） */
    private Long entryTypeId;

    /** 类别亲和 {类别:0~1}（jsonb，无序多标签） */
    private String cat;

    /** 拍型：铺垫/情绪/事件/反转/抉择/收束（WORLD 行 NULL，单值不权重化） */
    private String beatType;

    /** 节奏 0~1 锚点 {0.00,0.25,0.50,0.75,1.00}（0慢1快） */
    private BigDecimal pacing;

    /** 节奏来源：LLM / DERIVED / MANUAL */
    private String pacingSrc;

    /** 弧位胜任 {开头,中段,转折,收尾} 取值 0/0.5/1.0（jsonb） */
    private String arcFit;

    /** 可独立成篇 */
    private Boolean standalone;

    /** 血缘（冗余列供快速过滤，以素材表为准） */
    private Long srcNovelId;
}
