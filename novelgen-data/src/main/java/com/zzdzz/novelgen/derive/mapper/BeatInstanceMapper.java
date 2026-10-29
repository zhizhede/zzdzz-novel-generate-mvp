package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import org.apache.ibatis.annotations.Param;

/** beat_instance（衍生域）Mapper：全标量列，标准 CRUD。 */
public interface BeatInstanceMapper extends BaseMapper<BeatInstanceDO> {

    long insertFull(@Param("p") BeatInstanceDO beat);

    int updateRoute(@Param("p") BeatInstanceDO beat);

    int updateFill(@Param("id") long id, @Param("filledText") String filledText,
                   @Param("status") String status);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int resetFilledByRun(@Param("runId") long runId);

    /** DRAFT 局部回炉：只重置问题章内的拍（filled_text/status）。 */
    int resetFilledByChapters(@Param("runId") long runId,
                              @Param("chapterNos") java.util.List<Integer> chapterNos);

    int clearByVolume(@Param("runId") long runId, @Param("volumeNo") int volumeNo);

    /** 计划投影：删除本 run 的旧拍实例。 */
    int deleteByRun(@Param("runId") long runId);
    /** 正文闭环：仅当前 run+beat_key+active plan revision 可写场景正文。 */
    int updateFilledIfRevision(@Param("runId") long runId,
                               @Param("beatKey") String beatKey,
                               @Param("planRevision") int planRevision,
                               @Param("filledText") String filledText,
                               @Param("status") String status);

}
