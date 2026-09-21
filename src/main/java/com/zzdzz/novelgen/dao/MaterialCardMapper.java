package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.MaterialCardDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** material_cards 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/MaterialCardMapper.xml。 */
public interface MaterialCardMapper extends BaseMapper<MaterialCardDTO> {

    List<MaterialCardDTO> listByNovel(@Param("novelId") long novelId, @Param("kind") String kind);

    MaterialCardDTO findById(@Param("id") long id);

    boolean exists(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    int softDelete(@Param("id") long id);

    boolean hasCards(@Param("novelId") long novelId);

    int insert(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name,
               @Param("aliases") String aliases, @Param("summary") String summary, @Param("contentMd") String contentMd,
               @Param("pinned") boolean pinned, @Param("status") String status, @Param("sourceChapter") Integer sourceChapter);

    int update(@Param("id") long id, @Param("name") String name, @Param("aliases") String aliases,
               @Param("summary") String summary, @Param("contentMd") String contentMd, @Param("pinned") Boolean pinned,
               @Param("status") String status, @Param("sourceChapter") Integer sourceChapter);
}
