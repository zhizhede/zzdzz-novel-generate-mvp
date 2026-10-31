package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** derive_story_obligation Mapper：JSONB payload 走 XML 显式 ::jsonb。 */
public interface DeriveStoryObligationMapper extends BaseMapper<DeriveStoryObligationDO> {

    int insertFull(@Param("p") DeriveStoryObligationDO row);

    int deleteByRun(@Param("runId") long runId);

    List<DeriveStoryObligationDO> selectByRun(@Param("runId") long runId);
}
