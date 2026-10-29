package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveOrganizationDO;
import org.apache.ibatis.annotations.Param;

/** derive_organization 持久化；members/contract JSONB 显式 cast。 */
public interface DeriveOrganizationMapper extends BaseMapper<DeriveOrganizationDO> {
    long insertFull(@Param("p") DeriveOrganizationDO row);
}