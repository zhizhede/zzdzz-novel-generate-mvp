package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveWorldInstitutionDO;
import org.apache.ibatis.annotations.Param;

/** derive_world_institution 持久化；affected_characters/contract JSONB 显式 cast。 */
public interface DeriveWorldInstitutionMapper extends BaseMapper<DeriveWorldInstitutionDO> {
    long insertFull(@Param("p") DeriveWorldInstitutionDO row);
}