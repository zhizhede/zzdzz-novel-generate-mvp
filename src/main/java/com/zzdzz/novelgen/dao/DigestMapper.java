package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.DigestDO;
import com.zzdzz.novelgen.service.data.DigestDataService;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** digests 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/DigestMapper.xml。 */
public interface DigestMapper extends BaseMapper<DigestDO> {

    int updateContent(@Param("id") long id, @Param("contentMd") String contentMd, @Param("factsJson") String factsJson);

    List<String> findRecent(@Param("novelId") long novelId, @Param("beforeChapter") int beforeChapter, @Param("n") int n);

    int insert(@Param("chapterId") long chapterId, @Param("contentMd") String contentMd, @Param("factsJson") String factsJson);

    boolean existsByChapter(@Param("chapterId") long chapterId);

    List<DigestDataService.DigestItem> listByNovel(@Param("novelId") long novelId);
}
