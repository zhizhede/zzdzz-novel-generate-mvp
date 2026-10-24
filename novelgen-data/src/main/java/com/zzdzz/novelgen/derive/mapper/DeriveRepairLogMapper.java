package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveRepairLogDO;
import org.apache.ibatis.annotations.Param;

/** derive_repair_log 持久化；JSONB 审计字段走显式 SQL。 */
public interface DeriveRepairLogMapper extends BaseMapper<DeriveRepairLogDO> {

    long insertFull(@Param("p") DeriveRepairLogDO repair);
}