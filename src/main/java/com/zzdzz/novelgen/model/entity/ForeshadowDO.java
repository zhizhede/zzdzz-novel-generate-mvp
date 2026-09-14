package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ForeshadowDO。 */
@Data
@TableName(value = "foreshadows")
public class ForeshadowDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long novelId;
    private String code;
    private String content;
    private Integer plantedIn;
    private Integer recoveredIn;
    private Integer proposedIn;
    private String status;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public long novelId() {
        return getNovelId();
    }

    @Deprecated
    public String code() {
        return getCode();
    }

    @Deprecated
    public String content() {
        return getContent();
    }

    @Deprecated
    public Integer plantedIn() {
        return getPlantedIn();
    }

    @Deprecated
    public Integer recoveredIn() {
        return getRecoveredIn();
    }

    @Deprecated
    public Integer proposedIn() {
        return getProposedIn();
    }

    @Deprecated
    public String status() {
        return getStatus();
    }
}
