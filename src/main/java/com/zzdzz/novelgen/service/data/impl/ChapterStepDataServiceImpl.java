package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.ChapterStepMapper;
import com.zzdzz.novelgen.model.entity.ChapterStepDO;
import com.zzdzz.novelgen.service.data.ChapterStepDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 章节步骤状态数据服务实现。查询用列名 Wrapper（boolean isDeleted 属性不入 MP lambda 缓存）。 */
@Service
public class ChapterStepDataServiceImpl extends ServiceImpl<ChapterStepMapper, ChapterStepDO>
        implements ChapterStepDataService {

    private QueryWrapper<ChapterStepDO> whereChapter(long chapterId) {
        return new QueryWrapper<ChapterStepDO>()
                .eq("chapter_id", chapterId)
                .eq("is_deleted", false);
    }

    @Override
    public Long start(long novelId, long chapterId, int chapterNo, String step, String subKey, int attempt) {
        ChapterStepDO row = new ChapterStepDO();
        row.setNovelId(novelId);
        row.setChapterId(chapterId);
        row.setChapterNo(chapterNo);
        row.setStep(step);
        row.setSubKey(subKey);
        row.setAttempt(attempt);
        row.setStatus("RUNNING");
        save(row);
        return row.getId();
    }

    @Override
    public void finish(long stepId, String status, String detailJson) {
        ChapterStepDO patch = new ChapterStepDO();
        patch.setId(stepId);
        patch.setStatus(status);
        patch.setDetail(detailJson);
        updateById(patch);
    }

    @Override
    public List<ChapterStepDO> listByChapter(long chapterId) {
        return list(whereChapter(chapterId).orderByAsc("id"));
    }

    @Override
    public ChapterStepDO latestUnsuccessful(long chapterId) {
        // 每个 (step, subKey) 取最新一行，再在其中找最近一次失败/中断——
        // 同一步骤后续已 DONE 的旧失败不算（如 digest 第一次撞唯一键、第二次成功）
        java.util.LinkedHashMap<String, ChapterStepDO> latest = new java.util.LinkedHashMap<>();
        for (ChapterStepDO row : listByChapter(chapterId)) {
            latest.merge(row.getStep() + "|" + (row.getSubKey() == null ? "" : row.getSubKey()), row,
                    (a, b) -> b);
        }
        return latest.values().stream()
                .filter(r -> "FAILED".equals(r.getStatus()) || "INTERRUPTED".equals(r.getStatus()))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElse(null);
    }

    @Override
    public boolean hasRunning(long chapterId, String step) {
        return count(whereChapter(chapterId)
                .eq("step", step)
                .eq("status", "RUNNING")) > 0;
    }

    @Override
    public java.util.Optional<ChapterStepDO> latestRunningByNovelAndChapterNo(long novelId, int chapterNo) {
        return java.util.Optional.ofNullable(getOne(new QueryWrapper<ChapterStepDO>()
                .eq("novel_id", novelId)
                .eq("chapter_no", chapterNo)
                .eq("status", "RUNNING")
                .eq("is_deleted", false)
                .orderByDesc("id")
                .last("LIMIT 1"), false));
    }

    @Override
    public void finishRunningInterrupted(long chapterId) {
        update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ChapterStepDO>()
                .eq("chapter_id", chapterId)
                .eq("status", "RUNNING")
                .eq("is_deleted", false)
                .set("status", "INTERRUPTED")
                .set("detail", "{\"reason\": \"用户终止（硬中断）\"}")
                .set("update_time", java.time.LocalDateTime.now()));
    }
}
