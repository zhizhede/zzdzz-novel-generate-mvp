package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.EmbeddingDO;
import com.zzdzz.novelgen.service.data.EmbeddingDataService;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** embeddings 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/EmbeddingMapper.xml。 */
public interface EmbeddingMapper extends BaseMapper<EmbeddingDO> {

    int upsert(@Param("sourceType") String sourceType, @Param("sourceId") long sourceId,
               @Param("novelId") long novelId, @Param("chapterNo") Integer chapterNo,
               @Param("content") String content, @Param("vecStr") String vecStr);

    List<EmbeddingDataService.Hit> search(@Param("novelId") long novelId, @Param("vecStr") String vecStr,
                                     @Param("limit") int limit);

    List<EmbeddingDataService.MissingRow> findMissingDigests(@Param("novelId") long novelId, @Param("limit") int limit);

    List<EmbeddingDataService.MissingRow> findMissingCards(@Param("novelId") long novelId, @Param("limit") int limit);

    int countByNovel(@Param("novelId") long novelId);
}
