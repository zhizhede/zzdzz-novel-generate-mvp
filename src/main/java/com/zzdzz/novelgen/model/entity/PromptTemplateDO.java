package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** PromptTemplateDO。 */
@Data
@TableName(value = "prompt_templates")
public class PromptTemplateDO extends BaseDO {
    private String node;
    private String phase;
    private String title;
    private String content;
    private boolean exact;
    private int version;
    private boolean custom;
    private boolean enabled;

    @Deprecated
    public boolean exact() {
        return isExact();
    }

    @Deprecated
    public boolean custom() {
        return isCustom();
    }

    @Deprecated
    public boolean enabled() {
        return isEnabled();
    }
}
