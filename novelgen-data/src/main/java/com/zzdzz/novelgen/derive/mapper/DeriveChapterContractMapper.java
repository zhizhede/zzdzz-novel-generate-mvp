package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveChapterContractDO;
import org.apache.ibatis.annotations.Param;

public interface DeriveChapterContractMapper extends BaseMapper<DeriveChapterContractDO> {
    long insertFull(@Param("p") DeriveChapterContractDO contract);
}
