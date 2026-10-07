package com.zzdzz.novelgen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** canon_docs 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/CanonDocMapper.xml。 */
public interface CanonDocMapper extends BaseMapper<CanonDocDO> {

    boolean exists(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    int insert(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name, @Param("content") String content);

    String findFirstByKind(@Param("novelId") long novelId, @Param("kind") String kind);

    List<CanonDocDO> listByNovel(@Param("novelId") long novelId);

    /** 全库同类同名文档（规划资产页读「各书大纲」用）。 */
    List<CanonDocDO> listAliveByKindName(@Param("kind") String kind, @Param("name") String name);

    Long findId(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    String findContentByKindName(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    CanonDocDO findById(@Param("id") long id);

    int updateContent(@Param("id") long id, @Param("content") String content);

    int delete(@Param("id") long id);
}
