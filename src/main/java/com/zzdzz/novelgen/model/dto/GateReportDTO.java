package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** GateReportDTO。 */
@Data
@TableName(value = "gate_reports")
public class GateReportDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long chapterId;
    private Long sceneId;
    private String gateType;
    private int round;
    private boolean passed;
    private String result;
    private boolean isDeleted;
    private java.time.OffsetDateTime createTime;






    @Deprecated
    public boolean passed() {
        return isPassed();
    }

}
