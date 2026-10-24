package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import org.apache.ibatis.annotations.Param;

/** derive_story_thread 持久化；JSONB 合同显式 SQL。 */
public interface StoryThreadMapper extends BaseMapper<StoryThreadDO> {

    long insertFull(@Param("p") StoryThreadDO thread);

    int updateFull(@Param("p") StoryThreadDO thread);
}
