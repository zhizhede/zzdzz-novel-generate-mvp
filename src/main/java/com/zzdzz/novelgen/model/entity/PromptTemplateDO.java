package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** PromptTemplateDO。 */
@Data
@TableName(value = "prompt_templates")
public class PromptTemplateDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String node;
    private String phase;
    private String title;
    private String content;
    private boolean exact;
    private int version;
    private boolean custom;
    private boolean enabled;
    private boolean isDeleted;
    private OffsetDateTime updateTime;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public String node() {
        return getNode();
    }

    @Deprecated
    public String phase() {
        return getPhase();
    }

    @Deprecated
    public String title() {
        return getTitle();
    }

    @Deprecated
    public String content() {
        return getContent();
    }

    @Deprecated
    public boolean exact() {
        return isExact();
    }

    @Deprecated
    public int version() {
        return getVersion();
    }

    @Deprecated
    public boolean custom() {
        return isCustom();
    }

    @Deprecated
    public boolean enabled() {
        return isEnabled();
    }

    @Deprecated
    public OffsetDateTime updateTime() {
        return getUpdateTime();
    }
}
