package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** StylePackDTO。 */
@Data
@TableName(value = "style_packs")
public class StylePackDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String rulesMd;
    private String fingerprint;
    private boolean isDeleted;
    /** 预设模板（未被书引用）：应用到书 = 拷贝 fingerprint/gate_config/rules_md。 */
    private boolean isPreset;





}
