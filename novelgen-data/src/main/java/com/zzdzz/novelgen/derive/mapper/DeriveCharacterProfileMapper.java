package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterProfileDO;
import org.apache.ibatis.annotations.Param;

/** derive_character_profile 持久化；profile JSONB 显式 cast。 */
public interface DeriveCharacterProfileMapper extends BaseMapper<DeriveCharacterProfileDO> {
    long insertFull(@Param("p") DeriveCharacterProfileDO row);
}