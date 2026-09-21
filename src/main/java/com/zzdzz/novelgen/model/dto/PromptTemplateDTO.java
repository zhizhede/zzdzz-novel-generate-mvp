package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** PromptTemplateDTO。 */
@Data
@TableName(value = "prompt_templates")
public class PromptTemplateDTO {
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
