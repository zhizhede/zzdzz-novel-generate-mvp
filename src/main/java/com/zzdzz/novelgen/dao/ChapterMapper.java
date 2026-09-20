package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** chapters 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/ChapterMapper.xml。 */
public interface ChapterMapper extends BaseMapper<ChapterDO> {

    Integer maxChapterWithText(@Param("novelId") long novelId);

    ChapterDO findByNovelAndNo(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    List<ChapterDO> listSummaries(@Param("novelId") long novelId);

    List<ChapterDataService.ChapterTextRow> findOpeningRows(@Param("novelId") long novelId, @Param("maxChapterNo") int maxChapterNo);

    String findFullText(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    List<ChapterDataService.ApprovedNoDigest> findApprovedWithoutDigest();

    List<ChapterDataService.VolumeFactRow> listVolumeFacts(@Param("novelId") long novelId, @Param("volNo") int volNo);

    Long existsCount(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    int insertPlan(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo, @Param("volumeNo") Integer volumeNo,
                   @Param("arc") String arc, @Param("title") String title, @Param("goal") String goal,
                   @Param("hook") String hook, @Param("timeNote") String timeNote, @Param("ruleRefs") String ruleRefs,
                   @Param("foreshadowRefs") String foreshadowRefs, @Param("budgetMin") int budgetMin,
                   @Param("budgetMax") int budgetMax);

    int deleteGateReports(@Param("chapterId") long chapterId);

    int deleteScenes(@Param("chapterId") long chapterId);

    int deleteChapterSteps(@Param("chapterId") long chapterId);

    int markOutlined(@Param("chapterId") long chapterId, @Param("outlineYaml") String outlineYaml);

    /** 人工打回清场（流 A）：状态→NEW、清正文/章纲/轮次、落打回意见；调用方先删场景与门禁报告。 */
    int markRejected(@Param("chapterId") long chapterId, @Param("reason") String reason);

    /** 打回意见消费后清零（章纲提示词注入成功后调用）。 */
    int clearRejectReason(@Param("chapterId") long chapterId);

    int updatePlan(@Param("chapterId") long chapterId, @Param("volumeNo") Integer volumeNo, @Param("arc") String arc,
                   @Param("title") String title, @Param("goal") String goal, @Param("hook") String hook,
                   @Param("timeNote") String timeNote, @Param("budgetMin") int budgetMin, @Param("budgetMax") int budgetMax);

    int softDeletePlan(@Param("chapterId") long chapterId);

    int updateStatus(@Param("chapterId") long chapterId, @Param("status") String status);

    int updateStatusIf(@Param("chapterId") long chapterId, @Param("expect") String expect, @Param("to") String to);

    int updateStatusByNo(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo, @Param("status") String status);

    int updateReviewConfig(@Param("chapterId") long chapterId, @Param("reviewConfig") String reviewConfig);

    int saveFullText(@Param("chapterId") long chapterId, @Param("fullText") String fullText);

    List<ChapterDataService.ChapterTextRow> findDialogueRows(@Param("novelId") long novelId, @Param("maxChapterNo") int maxChapterNo);
}
