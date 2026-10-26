package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveGateReviewDO;
import org.apache.ibatis.annotations.Param;

public interface DeriveGateReviewMapper extends BaseMapper<DeriveGateReviewDO> {
    long insertFull(@Param("p") DeriveGateReviewDO review);
}
