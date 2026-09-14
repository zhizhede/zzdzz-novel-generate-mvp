package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** NovelDO。 */
@Data
@TableName(value = "novels")
public class NovelDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long userId;
    private String title;
    private String description;
    private Long stylePackId;
    private String approvalMode;
    private String status;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public long userId() {
        return getUserId();
    }

    @Deprecated
    public String title() {
        return getTitle();
    }

    @Deprecated
    public String description() {
        return getDescription();
    }

    @Deprecated
    public Long stylePackId() {
        return getStylePackId();
    }

    @Deprecated
    public String approvalMode() {
        return getApprovalMode();
    }

    @Deprecated
    public String status() {
        return getStatus();
    }
}
