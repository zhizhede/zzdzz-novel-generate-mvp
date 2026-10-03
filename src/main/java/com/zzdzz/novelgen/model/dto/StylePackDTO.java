package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** StylePackDTO。 */
@Data
@TableName(value = "style_packs")
public class StylePackDTO extends BaseDTO {
    private String name;
    private String description;
    private String rulesMd;
    private String fingerprint;
    /** 预设模板（未被书引用）：应用到书 = 拷贝 fingerprint/gate_config/rules_md。 */
    private boolean isPreset;
    private java.time.OffsetDateTime createTime;
    private java.time.OffsetDateTime updateTime;

}
