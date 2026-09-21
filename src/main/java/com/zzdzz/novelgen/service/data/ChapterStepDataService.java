package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.ChapterStepDTO;

import java.util.List;

/** 章节步骤状态数据服务（契约②）：每步每次尝试一行。 */
public interface ChapterStepDataService extends IService<ChapterStepDTO> {

    /** 开一步：插 RUNNING 行，返回行 id（终态时 finishStep 回填）。 */
    Long start(long novelId, long chapterId, int chapterNo, String step, String subKey, int attempt);

    /** 步骤终态：DONE/FAILED/INTERRUPTED，detail 为 JSON 文本（失败原因原文等）。 */
    void finish(long stepId, String status, String detailJson);

    /** 某章全部步骤行（按创建序），章详情「步骤」tab 用。 */
    List<ChapterStepDTO> listByChapter(long chapterId);

    /** 最近一条非成功终态行（FAILED/INTERRUPTED），一屏 failureBrief 用；无则 null。 */
    ChapterStepDTO latestUnsuccessful(long chapterId);

    /** 某章某步是否存在 RUNNING 行（digest 进行中判定等）。 */
    boolean hasRunning(long chapterId, String step);

    /** 某书某章最新 RUNNING 行（工作台「当前阶段」读模型）；无则空。 */
    java.util.Optional<ChapterStepDTO> latestRunningByNovelAndChapterNo(long novelId, int chapterNo);

    /** 中断收尾：该章全部 RUNNING 行置 INTERRUPTED（硬中断异常路径兜底）。 */
    void finishRunningInterrupted(long chapterId);
}
