package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.GateReportDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** gate_reports 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/GateReportMapper.xml。 */
public interface GateReportMapper extends BaseMapper<GateReportDO> {

    int insert(@Param("chapterId") long chapterId, @Param("sceneId") Long sceneId,
               @Param("gateType") String gateType, @Param("round") int round,
               @Param("passed") boolean passed, @Param("resultJson") String resultJson);

    int deleteByChapter(@Param("chapterId") long chapterId);

    String findLatestFailureJson(@Param("chapterId") long chapterId);

    String findLatestSceneFailureJson(@Param("chapterId") long chapterId, @Param("sceneId") long sceneId);

    List<Map<String, Object>> findLatestChapterReport(@Param("chapterId") long chapterId);

    List<Map<String, Object>> findLatestChapterReview(@Param("chapterId") long chapterId);

    List<GateReportDO> listByChapter(@Param("chapterId") long chapterId);
}
