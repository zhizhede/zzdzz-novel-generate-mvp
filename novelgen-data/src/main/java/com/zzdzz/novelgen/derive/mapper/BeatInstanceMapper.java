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

    int clearByVolume(@Param("runId") long runId, @Param("volumeNo") int volumeNo);
}
