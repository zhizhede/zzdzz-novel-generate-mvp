package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** GateReportDTO。 */
@Data
@TableName(value = "gate_reports")
public class GateReportDTO extends BaseDTO {
    private long chapterId;
    private Long sceneId;
    private String gateType;
    private int round;
    private boolean passed;
    private String result;
    private java.time.OffsetDateTime createTime;






    @Deprecated
    public boolean passed() {
        return isPassed();
    }

}
