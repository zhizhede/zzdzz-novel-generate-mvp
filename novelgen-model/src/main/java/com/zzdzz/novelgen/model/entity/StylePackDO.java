package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** StylePackDO。 */
@Data
@TableName(value = "style_packs")
public class StylePackDO extends BaseDO {
    private String name;
    private String description;
    private String rulesMd;
    private String fingerprint;
    /** 预设模板（未被书引用）：应用到书 = 拷贝 fingerprint/gate_config/rules_md。 */
    private boolean isPreset;
}
