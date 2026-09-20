package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.ChapterMapper;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** chapters 数据服务实现。 */
@Service
public class ChapterDataServiceImpl extends ServiceImpl<ChapterMapper, ChapterDO>
        implements ChapterDataService {

    @Override
    public Optional<ChapterDO> find(long novelId, int chapterNo) {
        return Optional.ofNullable(baseMapper.findByNovelAndNo(novelId, chapterNo));
    }

    @Override
    public Optional<ChapterDO> findById(long chapterId) {
        return Optional.ofNullable(getById(chapterId));
    }

    @Override
    public List<ChapterDO> listSummariesByNovel(long novelId) {
        return baseMapper.listSummaries(novelId);
    }

    @Override
    public List<ChapterDataService.VolumeFactRow> listVolumeFacts(long novelId, int volNo) {
        return baseMapper.listVolumeFacts(novelId, volNo);
    }

    @Override
    public Integer maxChapterWithText(long novelId) {
        return baseMapper.maxChapterWithText(novelId);
    }

    @Override
    public boolean exists(long novelId, int chapterNo) {
        Long count = baseMapper.existsCount(novelId, chapterNo);
        return count != null && count > 0;
    }

    @Override
    public void insertPlan(long novelId, int chapterNo, Integer volumeNo, String arc, String title,
                           String goal, String hook, String timeNote, String ruleRefs, String foreshadowRefs,
                           int budgetMin, int budgetMax) {
        baseMapper.insertPlan(novelId, chapterNo, volumeNo, arc, title, goal, hook, timeNote,
                ruleRefs, foreshadowRefs, budgetMin, budgetMax);
    }

    @Override
    public void resetForReoutline(long chapterId, String outlineYaml) {
        baseMapper.deleteGateReports(chapterId);
        baseMapper.deleteScenes(chapterId);
        baseMapper.markOutlined(chapterId, outlineYaml);
    }

    @Override
    public void rejectReset(long chapterId, String reason) {
        baseMapper.deleteGateReports(chapterId);
        baseMapper.deleteScenes(chapterId);
        baseMapper.deleteChapterSteps(chapterId);
        baseMapper.markRejected(chapterId, reason);
    }

    @Override
    public void clearRejectReason(long chapterId) {
        baseMapper.clearRejectReason(chapterId);
    }

    @Override
    public void updatePlan(long chapterId, Integer volumeNo, String arc, String title,
                           String goal, String hook, String timeNote, int budgetMin, int budgetMax) {
        baseMapper.updatePlan(chapterId, volumeNo, arc, title, goal, hook, timeNote, budgetMin, budgetMax);
    }

    @Override
    public void softDeletePlan(long chapterId) {
        baseMapper.softDeletePlan(chapterId);
    }

    @Override
    public void updateStatus(long chapterId, String status) {
        baseMapper.updateStatus(chapterId, status);
    }

    @Override
    public void updateReviewConfig(long chapterId, String reviewConfigJson) {
        if (reviewConfigJson != null && !reviewConfigJson.isBlank()) {
            baseMapper.updateReviewConfig(chapterId, reviewConfigJson);
        }
    }

    @Override
    public boolean updateStatusIf(long chapterId, String expect, String to) {
        return baseMapper.updateStatusIf(chapterId, expect, to) > 0;
    }

    @Override
    public void updateStatusByNo(long novelId, int chapterNo, String status) {
        baseMapper.updateStatusByNo(novelId, chapterNo, status);
    }

    @Override
    public void saveFullText(long chapterId, String fullText) {
        baseMapper.saveFullText(chapterId, fullText);
    }

    @Override
    public List<Opening> findOpeningLines(long novelId, int maxChapterNo) {
        List<Opening> out = new ArrayList<>();
        for (var r : baseMapper.findOpeningRows(novelId, maxChapterNo)) {
            out.add(new Opening(r.chapterNo(), firstLines(r.fullText())));
        }
        return out;
    }

    @Override
    public Opening findDialogueExcerpt(long novelId, int maxChapterNo, int lines) {
        record Row(int no, String text) {}
        List<Row> rows = new ArrayList<>();
        for (var m : baseMapper.findDialogueRows(novelId, maxChapterNo)) {
            rows.add(new Row(m.chapterNo(), m.fullText()));
        }
        Opening best = null;
        long bestScore = -1;
        for (Row r : rows) {
            List<String> ls = new ArrayList<>();
            for (String l : r.text().split("\n")) {
                if (!l.strip().isEmpty()) ls.add(l.strip());
            }
            for (int s = 0; s + lines <= ls.size(); s++) {
                long score = ls.subList(s, s + lines).stream().filter(l -> l.contains("「")).count();
                if (score > bestScore) {
                    bestScore = score;
                    best = new Opening(r.no(), String.join("\n", ls.subList(s, s + lines)));
                }
            }
        }
        return bestScore >= Math.max(3, lines * 0.6) ? best : null;
    }

    @Override
    public String findFullText(long novelId, int chapterNo) {
        return baseMapper.findFullText(novelId, chapterNo);
    }

    @Override
    public List<ApprovedNoDigest> findApprovedWithoutDigest() {
        return baseMapper.findApprovedWithoutDigest();
    }

    private static String firstLines(String fullText) {
        List<String> out = new ArrayList<>();
        for (String l : fullText.split("\n")) {
            if (!l.strip().isEmpty()) out.add(l.strip());
            if (out.size() == 3) break;
        }
        return String.join("\n", out);
    }
}
