package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterRelationshipDO;
import org.apache.ibatis.annotations.Param;

/** derive_character_relationship 持久化；JSONB 显式 cast。 */
public interface DeriveCharacterRelationshipMapper extends BaseMapper<DeriveCharacterRelationshipDO> {
    long insertFull(@Param("p") DeriveCharacterRelationshipDO row);
}