package com.zzdzz.novelgen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.CharacterStateDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** character_states 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/CharacterStateMapper.xml。 */
public interface CharacterStateMapper extends BaseMapper<CharacterStateDO> {

    /** 整章账重写：先删该章旧行（digest 重算＝原地覆盖，不留第二份账）。 */
    int deleteByChapter(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    /** 写一行（possessions 走 XML 显式 ::jsonb，MyBatis-Plus 的 varchar 绑定 PostgreSQL 不收）。 */
    int insertRow(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo, @Param("name") String name,
                  @Param("location") String location, @Param("possessions") String possessions);

    List<CharacterStateDO> listByChapter(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    List<CharacterStateDO> listByNovel(@Param("novelId") long novelId);

    /** 某名字在 beforeChapter 之前最近一次的状态行（跨章核对「上一章结束时此人在哪/带着什么」）。 */
    CharacterStateDO findLatestBeforeForName(@Param("novelId") long novelId, @Param("name") String name,
                                            @Param("beforeChapter") int beforeChapter);
}
