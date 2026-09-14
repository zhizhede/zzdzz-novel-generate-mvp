package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** GateReportDO。 */
@Data
@TableName(value = "gate_reports")
public class GateReportDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long chapterId;
    private Long sceneId;
    private String gateType;
    private int round;
    private boolean passed;
    private String result;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public long chapterId() {
        return getChapterId();
    }

    @Deprecated
    public Long sceneId() {
        return getSceneId();
    }

    @Deprecated
    public String gateType() {
        return getGateType();
    }

    @Deprecated
    public int round() {
        return getRound();
    }

    @Deprecated
    public boolean passed() {
        return isPassed();
    }

    @Deprecated
    public String result() {
        return getResult();
    }
}
