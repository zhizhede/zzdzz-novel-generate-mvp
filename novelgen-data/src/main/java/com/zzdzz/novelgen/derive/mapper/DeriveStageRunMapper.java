package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** derive_stage_run Mapper：条件 claim/finish/cancel 必须返回影响行数。 */
public interface DeriveStageRunMapper extends BaseMapper<DeriveStageRunDO> {

    /** 插入指定 attempt；同 run+stage+attempt 幂等。 */
    long insertFull(@Param("p") DeriveStageRunDO row);

    /** 同 run+stage 已存在任意 attempt 时插入被数据库忽略。 */
    int insertPendingIfAbsent(@Param("runId") long runId,
                              @Param("stage") String stage,
                              @Param("attempt") int attempt);

    DeriveStageRunDO findLatest(@Param("runId") long runId, @Param("stage") String stage);

    List<DeriveStageRunDO> findOpen(@Param("runId") long runId);

    List<DeriveStageRunDO> findAll(@Param("runId") long runId);

    /** 原子抢占 PENDING/FAILED，或回收已过期 RUNNING；同一 run 同时只许一个活动 owner。 */
    int claim(@Param("runId") long runId,
              @Param("stage") String stage,
              @Param("attempt") int attempt,
              @Param("leaseOwner") String leaseOwner,
              @Param("leaseUntil") OffsetDateTime leaseUntil);

    /** 仅 RUNNING 且 owner 匹配时允许 finish，并持久化 code/path/revision。 */
    int finish(@Param("id") long id,
               @Param("leaseOwner") String leaseOwner,
               @Param("state") String state,
               @Param("code") String code,
               @Param("path") String path,
               @Param("message") String message,
               @Param("outputRevision") Integer outputRevision);

    /** 取消全部未终态阶段；取消后不得再 claim。 */
    int cancelOpen(@Param("runId") long runId);
}
