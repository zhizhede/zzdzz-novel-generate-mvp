package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveChapterScheduleDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** derive_chapter_schedule Mapper：JSONB 写入走 XML 显式 ::jsonb。 */
public interface DeriveChapterScheduleMapper extends BaseMapper<DeriveChapterScheduleDO> {

    /** 插入一条日程行；JSONB 列必须显式 cast。 */
    int insertFull(@Param("p") DeriveChapterScheduleDO row);

    /** 删除某 run 的全部日程行（SCALE 回炉/清空规划时调用）。 */
    int deleteByRun(@Param("runId") long runId);

    /** 按章号升序读某 run 的全部日程行。 */
    List<DeriveChapterScheduleDO> selectByRun(@Param("runId") long runId);

    /** 读某 run 指定合同版本的日程行（版本守卫用）。 */
    List<DeriveChapterScheduleDO> selectByRunAndVersion(@Param("runId") long runId,
                                                        @Param("version") int version);
}
