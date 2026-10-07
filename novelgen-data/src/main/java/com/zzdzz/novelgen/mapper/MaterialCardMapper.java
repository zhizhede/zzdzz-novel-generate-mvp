package com.zzdzz.novelgen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** material_cards 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/MaterialCardMapper.xml。 */
public interface MaterialCardMapper extends BaseMapper<MaterialCardDO> {

    List<MaterialCardDO> listByNovel(@Param("novelId") long novelId, @Param("kind") String kind);

    MaterialCardDO findById(@Param("id") long id);

    boolean exists(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name);

    /** 除自己以外是否还有同名活卡：改名时的撞键前置校验（唯一索引 uq_material_cards_novel_kind_name）。 */
    boolean existsOther(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name,
                        @Param("excludeId") long excludeId);

    int delete(@Param("id") long id);

    boolean hasCards(@Param("novelId") long novelId);

    int insert(@Param("novelId") long novelId, @Param("kind") String kind, @Param("name") String name,
               @Param("aliases") String aliases, @Param("summary") String summary, @Param("contentMd") String contentMd,
               @Param("pinned") boolean pinned, @Param("status") String status, @Param("sourceChapter") Integer sourceChapter);

    int update(@Param("id") long id, @Param("name") String name, @Param("aliases") String aliases,
               @Param("summary") String summary, @Param("contentMd") String contentMd, @Param("pinned") Boolean pinned,
               @Param("status") String status, @Param("sourceChapter") Integer sourceChapter);
}
