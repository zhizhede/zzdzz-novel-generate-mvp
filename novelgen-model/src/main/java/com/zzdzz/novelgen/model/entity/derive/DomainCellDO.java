package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * domain_cell · 域格（v1.2 §3.8，④c 产物；域必须 seed 定死以治原型先验）。
 *
 * <p>jsonb 列（cats/refIds）与 text[] 列（beatTypes/counterWords）禁走 MP save()，
 * 一律 {@code DomainCellMapper.insertFull}。text[] 字段存 PG 数组字面量（"{铺垫,事件}" 形态，写入 ::text[] 转换）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("domain_cell")
public class DomainCellDO extends DeriveBaseDO {

    /** 域格名（如"食堂 / 小卖部"） */
    private String name;

    /** 组（校园域/都市域/…，同组跨格规则依据） */
    private String grp;

    /** 承载类别 {类别:0~1}（jsonb，成员判定≥阈值，与 label_index.cat 同轴同构） */
    private String cats;

    /** 可承载拍型（text[] 字面量，抽拍后校验用） */
    private String beatTypes;

    /** 关系动作落位说明（⑥b 提示词素材） */
    private String relationNote;

    /** 反域词（text[] 字面量；组级共享+格级增量，⑧ grep） */
    private String counterWords;

    /** 归纳依据 ["W-A02",…]（jsonb，多源归纳不挂单一书） */
    private String refIds;

    /** 启用 */
    private Boolean enabled;

    /** 排序 */
    private Integer sort;
}
