package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** StylePackDO。 */
@Data
@TableName(value = "style_packs")
public class StylePackDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String rulesMd;
    private String fingerprint;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public String name() {
        return getName();
    }

    @Deprecated
    public String description() {
        return getDescription();
    }

    @Deprecated
    public String rulesMd() {
        return getRulesMd();
    }

    @Deprecated
    public String fingerprint() {
        return getFingerprint();
    }
}
