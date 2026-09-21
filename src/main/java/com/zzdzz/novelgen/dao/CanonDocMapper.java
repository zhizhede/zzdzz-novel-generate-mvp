package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.CanonDocDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** canon_docs 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/CanonDocMapper.xml。 */
public interface CanonDocMapper extends BaseMapper<CanonDocDTO> {

    boolean exists(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    int insert(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name, @Param("content") String content);

    String findFirstByKind(@Param("novelId") long novelId, @Param("kind") String kind);

    List<CanonDocDTO> listByNovel(@Param("novelId") long novelId);

    Long findId(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    String findContentByKindName(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    CanonDocDTO findById(@Param("id") long id);

    int updateContent(@Param("id") long id, @Param("content") String content);

    int softDelete(@Param("id") long id);
}
