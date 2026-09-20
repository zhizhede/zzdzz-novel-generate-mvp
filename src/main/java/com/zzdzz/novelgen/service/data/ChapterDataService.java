package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.ChapterDO;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** chapters 数据服务接口（原 ChapterDAO）。 */
public interface ChapterDataService extends IService<ChapterDO> {

    /** 开篇样本行（非表行）：章节号 + 该章前 3 个非空行（人类手稿审美基准，注入第一场景用）。 */
    record Opening(int chapterNo, String firstLines) {
    }

    /** 异步审批占位后未落 digest 的章（进程重启遗留），启动自愈补跑用。 */
    record ApprovedNoDigest(long id, long novelId, int chapterNo) {
    }

    Optional<ChapterDO> find(long novelId, int chapterNo);

    Optional<ChapterDO> findById(long chapterId);

    /** 列表页摘要：full_text 不取（DO 中置 null）。 */
    List<ChapterDO> listSummariesByNovel(long novelId);

    /** 卷级复盘用：一卷各章的事实行（规划 + 实际产出）。 */
    List<Map<String, Object>> listVolumeFacts(long novelId, int volNo);

    /** 已有正文的最末章号（无任何正文时为 null）：卷纲规划必须接续其后来。 */
    Integer maxChapterWithText(long novelId);

    boolean exists(long novelId, int chapterNo);

    void insertPlan(long novelId, int chapterNo, Integer volumeNo, String arc, String title,
                    String goal, String hook, String timeNote, String ruleRefs, String foreshadowRefs,
                    int budgetMin, int budgetMax);

    /** 章纲回填并推进状态；同时清掉旧的场景与门禁报告（外键顺序：先报告后场景）。 */
    void resetForReoutline(long chapterId, String outlineYaml);

    /** 人工打回清场（流 A）：删场景/门禁报告/步骤行，状态→NEW，正文与章纲清空，意见落行。 */
    void rejectReset(long chapterId, String reason);

    /** 打回意见消费后清零（章纲提示词注入成功后调用）。 */
    void clearRejectReason(long chapterId);

    /** 卷纲规划编辑：改标题/目标/钩子/卷归属/字数预算（不改状态与正文）。 */
    void updatePlan(long chapterId, Integer volumeNo, String arc, String title,
                    String goal, String hook, String timeNote, int budgetMin, int budgetMax);

    /** 仅未动笔的规划行可删（软删）。 */
    void softDeletePlan(long chapterId);

    void updateStatus(long chapterId, String status);

    /** 条件状态推进（原子）：仅当当前状态等于 expect 才更新，返回是否生效——防并发重复审批等竞态。 */
    boolean updateStatusIf(long chapterId, String expect, String to);

    void updateStatusByNo(long novelId, int chapterNo, String status);

    void saveFullText(long chapterId, String fullText);

    /** 评审标准快照（JSON，生成开始时随章落库；历史章节保持 NULL）。 */
    void updateReviewConfig(long chapterId, String reviewConfigJson);

    /** 1..maxChapterNo 章的开篇样本（有正文的章）。 */
    List<Opening> findOpeningLines(long novelId, int maxChapterNo);

    /** 对白推进范例：1..maxChapterNo 中「最密集的连续 lines 行对白段」（至少六成行带对白才算范例）。 */
    Opening findDialogueExcerpt(long novelId, int maxChapterNo, int lines);

    /** 无该章（如第 1 章无“上一章”）时返回 null，由调用方决定降级文案。 */
    String findFullText(long novelId, int chapterNo);

    List<ApprovedNoDigest> findApprovedWithoutDigest();
}
