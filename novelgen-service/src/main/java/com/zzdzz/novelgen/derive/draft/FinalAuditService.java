package com.zzdzz.novelgen.derive.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStageRunMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditIssue;
import com.zzdzz.novelgen.derive.plan.audit.Severity;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanGraphRecords;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 最终正文硬/软审计：缺章、空章、过程话、revision drift 为 BLOCKER；重复段落为 ADVISORY。 */
@Service
public class FinalAuditService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REPEATED_LINE = Pattern.compile("^(.{20,})\\R\\1$", Pattern.MULTILINE);

    private final PlanSnapshotService snapshotService;
    private final RunMapper runMapper;
    private final ChapterMapper chapterMapper;
    private final DeriveStageRunMapper stageMapper;

    /** 创建最终审计服务。 */
    public FinalAuditService(PlanSnapshotService snapshotService,
                             RunMapper runMapper,
                             ChapterMapper chapterMapper,
                             DeriveStageRunMapper stageMapper) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.runMapper = Objects.requireNonNull(runMapper);
        this.chapterMapper = Objects.requireNonNull(chapterMapper);
        this.stageMapper = Objects.requireNonNull(stageMapper);
    }

    /** 只读审计当前 active revision 的完整正文。 */
    public FinalAuditReport audit(long runId) {
        DerivePlanSnapshotDO active;
        try {
            active = snapshotService.requireActive(runId);
        } catch (RuntimeException e) {
            return new FinalAuditReport(runId, 0, FinalAuditReport.FinalQuality.FAILED,
                    List.of(blocker("FINAL_AUDIT_BLOCKER", "run[" + runId + "]",
                            "无法读取 active plan revision", e.getMessage())));
        }
        CompiledStoryPlan plan = snapshotService.loadCompiled(runId, active.getRevision());
        RunDO run = runMapper.selectById(runId);
        if (run == null || run.getNovelId() == null) {
            return new FinalAuditReport(runId, active.getRevision(), FinalAuditReport.FinalQuality.FAILED, List.of(blocker("FINAL_AUDIT_BLOCKER", "run[" + runId + "]", "run 未关联正文目标书", "")));
        }
        List<ChapterDO> chapters = chapterMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChapterDO>()
                        .eq(ChapterDO::getNovelId, run.getNovelId())
                        .orderByAsc(ChapterDO::getChapterNo));
        Map<Integer, ChapterDO> byNo = new HashMap<>();
        for (ChapterDO chapter : chapters) {
            byNo.put(chapter.getChapterNo(), chapter);
        }

        List<PlanAuditIssue> issues = new ArrayList<>();
        for (PlanGraphRecords.PlannedChapter planned : plan.chapters()) {
            ChapterDO chapter = byNo.get(planned.chapterNo());
            String path = "chapter[" + planned.chapterNo() + "]";
            if (chapter == null) {
                issues.add(blocker("FINAL_AUDIT_BLOCKER", path, "计划章缺失", "missing"));
                continue;
            }
            if (chapter.getContent() == null || chapter.getContent().isBlank()) {
                issues.add(blocker("FINAL_AUDIT_EMPTY_CHAPTER", path, "章节正文为空", "blank"));
                continue;
            }
            if (!active.getRevision().equals(chapter.getPlanRevision())) {
                issues.add(blocker("FINAL_AUDIT_BLOCKER", path, "章节正文绑定旧 plan revision",
                        String.valueOf(chapter.getPlanRevision())));
                continue;
            }
            if (DraftTextRules.hasMetaLeak(chapter.getContent())) {
                issues.add(blocker("FINAL_AUDIT_META_LEAK", path, "正文含机器过程话或来源痕迹",
                        firstMeta(chapter.getContent())));
            }
            if (hasRepeatedParagraph(chapter.getContent())) {
                issues.add(advisory("FINAL_AUDIT_STYLE", path, "正文存在重复段落", "repeat"));
            }
        }

        boolean degraded = isDraftDegraded(runId);
        if (issues.stream().anyMatch(issue -> issue.severity() == Severity.BLOCKER)) {
            return new FinalAuditReport(runId, active.getRevision(), FinalAuditReport.FinalQuality.FAILED, issues);
        }
        if (degraded) {
            return new FinalAuditReport(runId, active.getRevision(),
                    FinalAuditReport.FinalQuality.DEGRADED, issues);
        }
        FinalAuditReport.FinalQuality quality = issues.isEmpty()
                ? FinalAuditReport.FinalQuality.PASS
                : FinalAuditReport.FinalQuality.ADVISORY;
        return new FinalAuditReport(runId, active.getRevision(), quality, issues);
    }

    private boolean isDraftDegraded(long runId) {
        DeriveStageRunDO draft = stageMapper.findLatest(runId, "DRAFT");
        if (draft == null || draft.getMessage() == null || draft.getMessage().isBlank()) {
            return false;
        }
        try {
            JsonNode json = MAPPER.readTree(draft.getMessage());
            return json.path("chaptersDegraded").asInt(0) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasRepeatedParagraph(String content) {
        if (content == null) {
            return false;
        }
        List<String> normalized = Arrays.stream(content.split("\\R"))
                .map(line -> line.replaceAll("\\s", ""))
                .filter(line -> line.length() >= 20)
                .toList();
        for (int i = 0; i < normalized.size(); i++) {
            for (int j = i + 1; j < normalized.size(); j++) {
                if (normalized.get(i).equals(normalized.get(j))) {
                    return true;
                }
            }
        }
        return REPEATED_LINE.matcher(content).find();
    }

    private static String firstMeta(String content) {
        return content == null || content.isBlank() ? "blank" : "machine";

    }

    private static PlanAuditIssue blocker(String code, String path, String message, String actual) {
        return new PlanAuditIssue(code, Severity.BLOCKER, path, null, null,
                PlanContracts.RepairScope.NONE, message, "", actual);
    }

    private static PlanAuditIssue advisory(String code, String path, String message, String actual) {
        return new PlanAuditIssue(code, Severity.ADVISORY, path, null, null,
                PlanContracts.RepairScope.NONE, message, "", actual);
    }
}

