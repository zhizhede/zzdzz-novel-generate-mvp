package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import org.apache.ibatis.annotations.Param;

/** derive_volume_plan：卷级规划行。 */
public interface VolumePlanMapper extends BaseMapper<VolumePlanDO> {

    int updatePlanning(@Param("p") VolumePlanDO plan);

    int updateContract(@Param("id") long id, @Param("contract") String contract);
}
