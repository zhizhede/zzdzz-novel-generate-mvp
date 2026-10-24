package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import org.apache.ibatis.annotations.Param;

/** derive_character_plan 持久化；JSONB 合同显式 SQL。 */
public interface CharacterPlanMapper extends BaseMapper<CharacterPlanDO> {

    long insertFull(@Param("p") CharacterPlanDO plan);

    int updateScalars(@Param("p") CharacterPlanDO plan);

    int updateContract(@Param("id") long id, @Param("contract") String contract);
}
