package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** world_kind · 世界观种类字典（v1.2 §3.6；V100 种子 RULE/FACT）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("world_kind")
public class WorldKindDO extends DeriveBaseDO {

    /** 种类代号（RULE=硬规则 / FACT=世界事实，唯一） */
    private String code;

    /** 种类名 */
    private String name;
}
