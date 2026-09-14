package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.PipelineEventDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** pipeline_events 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/PipelineEventMapper.xml。 */
public interface PipelineEventMapper extends BaseMapper<PipelineEventDO> {

    int insert(@Param("novelId") Long novelId, @Param("chapterNo") Integer chapterNo,
               @Param("stage") String stage, @Param("phase") String phase, @Param("payload") String payload);

    List<Map<String, Object>> list(@Param("novelId") Long novelId, @Param("chapterNo") Integer chapterNo,
                                   @Param("limit") int limit);
}
