package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.apache.ibatis.annotations.Param;

/**
 * run（衍生域）Mapper。⚠ jsonb 七列禁走 MP save()：
 * {@link #insertFull} 全量插入、{@link #updateWeld} 焊接后回填（均 XML 显式 ::jsonb）。
 */
public interface RunMapper extends BaseMapper<RunDO> {

    long insertFull(@Param("p") RunDO run);

    /** 5.3 焊接完成后回填：welded + status（SAMPLING→PLANNING）。 */
    int updateWeld(@Param("id") long id, @Param("welded") String welded);

    /** ⑥a 建成品书后回填 run.novel_id（status 不动，仍 PLANNING）。 */
    int updateNovelId(@Param("id") long id, @Param("novelId") long novelId);

    /** 状态机推进（⑥b→DRAFTING、⑦→QC、⑧→PASSED/REWORK/FAILED）；qc_round 一并可改。 */
    int updateStatus(@Param("id") long id, @Param("status") String status,
                     @Param("qcRound") Integer qcRound);

    /** L2 步状态回写（P6 立项闸 GATE 记录等；jsonb 显式 cast，AGENTS 坑 #13）。 */
    int updateStepStatus(@Param("id") long id, @Param("stepStatus") String stepStatus);
}
