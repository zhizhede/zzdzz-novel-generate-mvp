package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveBeatPoolDO;
import org.apache.ibatis.annotations.Param;

public interface DeriveBeatPoolMapper extends BaseMapper<DeriveBeatPoolDO> {
    long insertFull(@Param("p") DeriveBeatPoolDO beat);
}
