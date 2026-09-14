package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.WorldStateDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** world_states 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/WorldStateMapper.xml。 */
public interface WorldStateMapper extends BaseMapper<WorldStateDO> {

    int upsert(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo, @Param("state") String state);

    String findLatestBefore(@Param("novelId") long novelId, @Param("beforeChapter") int beforeChapter);

    List<Map<String, Object>> listByNovel(@Param("novelId") long novelId, @Param("limit") int limit);

    int updateByChapter(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo, @Param("stateJson") String stateJson);

    int insertRaw(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo, @Param("stateJson") String stateJson);
}
