package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveFactDO;
import org.apache.ibatis.annotations.Param;

/** derive_fact 持久化；JSONB 字段走显式 SQL。 */
public interface DeriveFactMapper extends BaseMapper<DeriveFactDO> {

    long insertFull(@Param("p") DeriveFactDO fact);

    int updateFull(@Param("p") DeriveFactDO fact);
}