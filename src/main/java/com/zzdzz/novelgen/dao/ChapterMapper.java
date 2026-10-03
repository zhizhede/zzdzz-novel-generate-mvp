package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.ChapterDTO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** chapters 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/ChapterMapper.xml。 */
public interface ChapterMapper extends BaseMapper<ChapterDTO> {

    Integer maxChapterWithText(@Param("novelId") long novelId);

    Integer nextPlannedChapterNo(@Param("novelId") long novelId, @Param("afterNo") int afterNo);

    Integer maxPlannedChapterNo(@Param("novelId") long novelId);

    Integer maxVolumeNo(@Param("novelId") long novelId);

    ChapterDTO findByNovelAndNo(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    List<ChapterDTO> listSummaries(@Param("novelId") long novelId);

    List<ChapterDataService.ChapterTextRow> findOpeningRows(@Param("novelId") long novelId, @Param("maxChapterNo") int maxChapterNo);

    /** 全书有正文的章（按章号升序）：按本书正文统计文风指纹用。 */
    List<ChapterDataService.ChapterTextRow> listTextsByNovel(@Param("novelId") long novelId);

    /** 全库规划行（书升序 + 章号升序，正文只取长度不取全文）：规划资产页读模型用。 */
    List<ChapterDataService.ChapterPlanRow> listPlanRows(@Param("novelId") Long novelId);

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

    /** 只写章纲、不动状态（给已有正文的章出纲用：不能让成品章退回「待生成」）。 */
    int updateOutlineYaml(@Param("chapterId") long chapterId, @Param("outlineYaml") String outlineYaml);

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
