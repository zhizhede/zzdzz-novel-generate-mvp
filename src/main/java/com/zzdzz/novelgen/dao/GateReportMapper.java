package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.GateReportDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** gate_reports 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/GateReportMapper.xml。 */
public interface GateReportMapper extends BaseMapper<GateReportDTO> {

    int insert(@Param("chapterId") long chapterId, @Param("sceneId") Long sceneId,
               @Param("gateType") String gateType, @Param("round") int round,
               @Param("passed") boolean passed, @Param("resultJson") String resultJson);

    int deleteByChapter(@Param("chapterId") long chapterId);

    String findLatestFailureJson(@Param("chapterId") long chapterId);

    String findLatestSceneFailureJson(@Param("chapterId") long chapterId, @Param("sceneId") long sceneId);

    List<GateReportDTO> findLatestChapterReport(@Param("chapterId") long chapterId);

    List<GateReportDTO> findLatestChapterReview(@Param("chapterId") long chapterId);

    List<GateReportDTO> listByChapter(@Param("chapterId") long chapterId);
}
