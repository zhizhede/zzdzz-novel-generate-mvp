package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 正文阶段持久执行账：stage/attempt/lease/错误路径与 revision 事实源。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_stage_run")
public class DeriveStageRunDO extends DeriveBaseDO {

    /** 所属运行。 */
    private Long runId;

    /** 阶段：DRAFT / FINAL_AUDIT / COMPLETE。 */
    private String stage;

    /** PENDING / RUNNING / DONE / FAILED / CANCELLED。 */
    private String status;

    /** 同 run+stage 的执行序号，从 0 开始，失败重试单调递增。 */
    private Integer attempt;

    /** 租约持有者 host:pid:uuid。 */
    private String leaseOwner;

    /** 租约到期时间；过期后允许其他 owner 恢复。 */
    private OffsetDateTime leaseUntil;

    /** 稳定错误码。 */
    private String errorCode;

    /** 可定位错误路径，如 chapter[2].scene[3]。 */
    private String errorPath;

    /** 错误详情或完成摘要。 */
    private String message;

    /** 本阶段完成时绑定的 active plan revision。 */
    private Integer outputRevision;
}
