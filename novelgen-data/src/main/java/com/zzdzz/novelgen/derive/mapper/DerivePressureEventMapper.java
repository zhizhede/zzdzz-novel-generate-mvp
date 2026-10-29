package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DerivePressureEventDO;
import org.apache.ibatis.annotations.Param;

/** derive_pressure_event 持久化；状态和变化 JSONB 显式 cast。 */
public interface DerivePressureEventMapper extends BaseMapper<DerivePressureEventDO> {
    long insertFull(@Param("p") DerivePressureEventDO row);
}