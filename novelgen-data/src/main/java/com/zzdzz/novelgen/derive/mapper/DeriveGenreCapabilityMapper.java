package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreCapabilityDO;
import org.apache.ibatis.annotations.Param;

/** derive_genre_capability 持久化；JSONB 显式 cast。 */
public interface DeriveGenreCapabilityMapper extends BaseMapper<DeriveGenreCapabilityDO> {
    long insertFull(@Param("p") DeriveGenreCapabilityDO row);
}