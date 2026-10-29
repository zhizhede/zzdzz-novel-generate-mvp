package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import org.apache.ibatis.annotations.Param;

/** derive_plan_snapshot Mapper：graph/admission_report 为 JSONB，显式 SQL 禁走 MP save()。 */
public interface DerivePlanSnapshotMapper extends BaseMapper<DerivePlanSnapshotDO> {

    /** 插入 DRAFT 快照并返回 id；graph 必须显式 ::jsonb。 */
    long insertFull(@Param("p") DerivePlanSnapshotDO snapshot);

    /** 读取某 run 的最大 revision；无快照时返回 null。 */
    DerivePlanSnapshotDO findMaxRevision(@Param("runId") long runId);

    /** 按 run+revision 读取快照；不存在时返回 null。 */
    DerivePlanSnapshotDO findRevision(@Param("runId") long runId,
                                      @Param("revision") int revision);

    /** 只允许把 DRAFT 准入为 ADMITTED，并落审计报告与最终 plan hash。 */
    int admit(@Param("runId") long runId,
              @Param("revision") int revision,
              @Param("reportJson") String reportJson,
              @Param("planHash") String planHash);

    /** 新版本准入前把旧 ADMITTED 快照统一降为 SUPERSEDED。 */
    int supersedeOtherAdmitted(@Param("runId") long runId,
                               @Param("keepRevision") int keepRevision);

    /** 更新 run 的唯一 active plan revision 指针。 */
    int updateRunActiveRevision(@Param("runId") long runId,
                                @Param("revision") int revision);
}