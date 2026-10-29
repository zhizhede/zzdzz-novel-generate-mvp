package com.zzdzz.novelgen.derive.plan.compiler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把已编译计划投影到既有 chapters/beat_instance。
 *
 * <p>正文保护是硬前置：只要目标书存在任何非空 content，投影立即拒绝；投影只删除/重建
 * 目标 run 与目标 novel 的计划骨架，并为所有行写入 plan_revision。
 */
@Service
public class PlanProjectionService {

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final ObjectMapper objectMapper;

    /** 创建计划投影服务。 */
    public PlanProjectionService(
            RunMapper runMapper,
            NovelMapper novelMapper,
            ChapterMapper chapterMapper,
            BeatInstanceMapper beatInstanceMapper,
            ObjectMapper objectMapper) {
        this.runMapper = Objects.requireNonNull(runMapper);
        this.novelMapper = Objects.requireNonNull(novelMapper);
        this.chapterMapper = Objects.requireNonNull(chapterMapper);
        this.beatInstanceMapper = Objects.requireNonNull(beatInstanceMapper);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** 原子激活一个计划 revision 到现有章/拍表。 */
    @Transactional
    public void activate(long runId, long novelId, int revision, CompiledStoryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.runId() != runId || revision <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "计划投影 runId/revision 不匹配");
        }
        RunDO run = runMapper.selectById(runId);
        if (run == null || !Long.valueOf(novelId).equals(run.getNovelId())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 未关联指定 novel: " + runId);
        }
        NovelDO novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "成品书不存在: " + novelId);
        }
        List<ChapterDO> existing = chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterDO>()
                        .eq(ChapterDO::getNovelId, novelId)
                        .orderByAsc(ChapterDO::getChapterNo));
        long nonEmpty = existing.stream()
                .filter(chapter -> chapter.getContent() != null && !chapter.getContent().isBlank())
                .count();
        if (nonEmpty > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "计划投影拒绝覆盖已有正文：novel=" + novelId + "，非空章=" + nonEmpty);
        }

        int deletedBeats = beatInstanceMapper.deleteByRun(runId);
        int deletedChapters = chapterMapper.deleteByNovelId(novelId);
        if (!existing.isEmpty() && deletedChapters != existing.size()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "计划投影删除章行数不符：expected=" + existing.size() + ", actual=" + deletedChapters);
        }

        List<Long> chapterIds = new ArrayList<>();
        for (PlanGraphRecords.PlannedChapter chapter : plan.chapters()) {
            ChapterDO row = new ChapterDO();
            row.setNovelId(novelId);
            row.setChapterNo(chapter.chapterNo());
            row.setVolumeNo(chapter.volumeNo());
            row.setTitle(chapter.title());
            row.setBudget(chapter.budget());
            row.setStatus("PLANNED");
            row.setPlanRevision(revision);
            long id = chapterMapper.insertPlanChapter(row);
            if (id <= 0) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "计划投影写章失败：chapter=" + chapter.chapterNo());
            }
            chapterIds.add(id);
        }

        for (PlanGraphRecords.PlannedBeat beat : plan.beats()) {
            BeatInstanceDO row = new BeatInstanceDO();
            row.setRunId(runId);
            row.setSeq(beat.seq());
            row.setSource(beat.source());
            row.setAssetId(beat.assetId());
            row.setArcId(beat.arcId());
            row.setArcSegNo(beat.arcSegNo());
            row.setBeatType(beat.beatType());
            row.setPacing(beat.pacing());
            row.setChapterNo(beat.chapterNo());
            row.setVolumeNo(beat.volumeNo());
            row.setStatus("PLANNED");
            row.setBeatKey(beat.beatKey());
            row.setChapterSeq(beat.sceneNo());
            row.setPackVersion(revision);
            row.setNoveltyKey(beat.beatKey());
            row.setStoryLayer(beat.storyLayer().name());
            row.setBeatRole(beat.beatRole().name());
            row.setRouteContract(toRouteContract(beat));
            row.setPlanRevision(revision);
            long id = beatInstanceMapper.insertFull(row);
            if (id <= 0) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "计划投影写拍失败：beat=" + beat.beatKey());
            }
        }

        if (novelMapper.updateChapterCount(novelId, plan.chapters().size()) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "计划投影同步章数失败：novel=" + novelId);
        }
        if (deletedBeats < 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "删除旧拍实例返回异常行数");
        }
    }

    private String toRouteContract(PlanGraphRecords.PlannedBeat beat) {
        try {
            return objectMapper.writeValueAsString(beat);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "计划拍 route_contract 序列化失败：" + beat.beatKey(), e);
        }
    }
}
