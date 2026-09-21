package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.SceneDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** chapter_scenes 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/SceneMapper.xml。 */
public interface SceneMapper extends BaseMapper<SceneDTO> {

    int countByChapter(@Param("chapterId") long chapterId);

    List<SceneDTO> findByChapter(@Param("chapterId") long chapterId);

    Long findId(@Param("chapterId") long chapterId, @Param("sceneNo") int sceneNo);

    int saveDraft(@Param("chapterId") long chapterId, @Param("sceneNo") int sceneNo, @Param("draftText") String draftText);

    int applyRevise(@Param("sceneId") long sceneId, @Param("draftText") String draftText);

    int updateGateStatus(@Param("sceneId") long sceneId, @Param("status") String status);

    List<String> findPassedDrafts(@Param("chapterId") long chapterId);

    int deleteByChapter(@Param("chapterId") long chapterId);

    int insertScene(@Param("chapterId") long chapterId, @Param("sceneNo") int sceneNo, @Param("goal") String goal,
                    @Param("present") String present, @Param("mustReveal") String mustReveal,
                    @Param("mustNot") String mustNot, @Param("words") int words);
}
