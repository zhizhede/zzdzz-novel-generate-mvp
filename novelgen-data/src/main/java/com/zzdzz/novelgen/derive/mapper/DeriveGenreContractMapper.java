package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreContractDO;
import org.apache.ibatis.annotations.Param;

/** derive_genre_contract Mapper：scope/payload JSONB 走 XML 显式 ::jsonb。 */
public interface DeriveGenreContractMapper extends BaseMapper<DeriveGenreContractDO> {

    int insertFull(@Param("p") DeriveGenreContractDO row);

    int deleteByRun(@Param("runId") long runId);

    DeriveGenreContractDO findByRun(@Param("runId") long runId);
}
