package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.model.enums.GateType;
import com.zzdzz.novelgen.model.enums.ChapterStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ChapterStepDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.vo.ChapterDetailVO;
import com.zzdzz.novelgen.model.vo.ChapterListItemVO;
import com.zzdzz.novelgen.model.vo.ChapterStepVO;
import com.zzdzz.novelgen.model.vo.GateReportVO;
import com.zzdzz.novelgen.model.vo.LlmTotalsVO;
import com.zzdzz.novelgen.model.vo.ReviewVO;
import com.zzdzz.novelgen.model.vo.SceneVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 章查询：列表摘要 + 详情聚合（正文/场景/最新章级门禁/本章 LLM 用量）+ 生成档案（trace）。 */
@Service
@RequiredArgsConstructor
public class ChapterQueryService {

    private final ChapterDataService chapterData;
    private final SceneDataService sceneData;
    private final GateReportDataService gateReportData;
    private final LlmCallLogDataService llmCallLogData;
    private final ChapterStepDataService stepData;
    private final LlmNodeConfigService nodeConfig;
    private final ObjectMapper mapper;


    public List<ChapterListItemVO> listByNovel(long novelId) {
        return chapterData.listSummariesByNovel(novelId).stream()
                .map(c -> new ChapterListItemVO(c.getId(), c.getChapterNo(), c.getTitle(), c.getStatus(),
                        c.getRound(), c.getBudgetMin(), c.getBudgetMax()))
                .toList();
    }

    /** 待审批聚合（流 A 插队/人工通道）：manual/auto 累积的 PENDING_APPROVAL 章。 */
    public List<ChapterListItemVO> pendingApprovals(long novelId) {
        return listByNovel(novelId).stream()
                .filter(c -> ChapterStatus.PENDING_APPROVAL.is(c.status()))
                .toList();
    }

    public ChapterDetailVO detail(long chapterId) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));
        List<SceneVO> scenes = sceneData.findByChapter(chapterId).stream()
                .map(s -> new SceneVO(s.getId(), s.getSceneNo(), s.getGoal(), s.getDraftText(),
                        s.getGateStatus(), s.getRevisionRound()))
                .toList();
        // 流 B 一屏答案：步骤状态行 + 打回意见 + 失败摘要 + digest 进行中
        List<ChapterStepVO> steps = stepData.listByChapter(chapterId).stream()
                .map(s -> new ChapterStepVO(s.getStep(), s.getSubKey(),
                        s.getAttempt() == null ? 1 : s.getAttempt(), s.getStatus(),
                        s.getDetail(), String.valueOf(s.getUpdateTime())))
                .toList();
        return new ChapterDetailVO(ch.getId(), ch.getChapterNo(), ch.getTitle(), ch.getStatus(), ch.getRound(),
                ch.getBudgetMin(), ch.getBudgetMax(), ch.getFullText(), scenes,
                latestGateReport(chapterId), latestReview(chapterId),
                toTotalsVO(llmCallLogData.totalsBy(null, chapterId)), ch.getReviewConfig(),
                steps, ch.getRejectReason(), failureBrief(chapterId),
                stepData.hasRunning(chapterId, "DIGEST"));
    }

    /** 章生成档案：steps + calls（仅元数据，全文走台账详情）+ checks 全轮次 + 按节点小计。 */
    public com.zzdzz.novelgen.model.vo.ChapterTraceVO trace(long chapterId) {
        ChapterDO ch = chapterData.findById(chapterId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterId));

        List<com.zzdzz.novelgen.model.vo.ChapterTraceVO.StepItem> steps = stepData.listByChapter(chapterId).stream()
                .map(s -> new com.zzdzz.novelgen.model.vo.ChapterTraceVO.StepItem(
                        s.getStep(), s.getSubKey(),
                        s.getAttempt() == null ? 1 : s.getAttempt(), s.getStatus(), s.getDetail(),
                        iso(s.getCreateTime()), iso(s.getUpdateTime())))
                .toList();

        List<com.zzdzz.novelgen.model.vo.ChapterTraceVO.CallItem> calls = new java.util.ArrayList<>(
                llmCallLogData.findPage(null, chapterId, 500, 0).stream()
                        .map(l -> new com.zzdzz.novelgen.model.vo.ChapterTraceVO.CallItem(
                                l.getId(), l.getNode(), l.getModel(), l.getStatus(),
                                l.getPromptTokens(), l.getCompletionTokens(), l.getTotalTokens(), l.getCachedTokens(),
                                l.getLatencyMs(), nodeConfig.costOf(l), iso(l.getCreateTime())))
                        .toList());
        java.util.Collections.reverse(calls);   // findPage 为 id DESC，档案要时间正序

        List<com.zzdzz.novelgen.model.vo.ChapterTraceVO.CheckItem> checks = gateReportData.listByChapter(chapterId).stream()
                .map(g -> new com.zzdzz.novelgen.model.vo.ChapterTraceVO.CheckItem(
                        g.getSceneId(), g.getGateType(), g.getRound(), g.isPassed(),
                        parseJson(g.getResult()), iso(g.getCreateTime())))
                .toList();

        // 按节点小计（calls 已正序；成本无价目行的节点计 null）
        Map<String, long[]> acc = new java.util.LinkedHashMap<>();
        Map<String, double[]> cost = new java.util.HashMap<>();
        for (var c : calls) {
            long[] a = acc.computeIfAbsent(c.node(), k -> new long[2]);
            a[0]++;
            a[1] += c.totalTokens();
            if (c.cost() != null) {
                double[] cc = cost.computeIfAbsent(c.node(), k -> new double[1]);
                cc[0] += c.cost();
            }
        }
        List<com.zzdzz.novelgen.model.vo.ChapterTraceVO.NodeStatItem> nodeStats = acc.entrySet().stream()
                .map(e -> new com.zzdzz.novelgen.model.vo.ChapterTraceVO.NodeStatItem(
                        e.getKey(), (int) e.getValue()[0], e.getValue()[1],
                        cost.containsKey(e.getKey()) ? Math.round(cost.get(e.getKey())[0] * 1e6) / 1e6 : null))
                .toList();

        return new com.zzdzz.novelgen.model.vo.ChapterTraceVO(ch.getId(), ch.getChapterNo(), ch.getTitle(),
                ch.getStatus(), steps, calls, checks, nodeStats);
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.valueToTree(java.util.Map.of("unparsed", raw));
        }
    }

    private static String iso(java.time.OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    /** 最近一次失败/中断步骤的原因原文（流 B 一屏答案；无则 null）。 */
    private String failureBrief(long chapterId) {
        var row = stepData.latestUnsuccessful(chapterId);
        if (row == null || row.getDetail() == null) return null;
        try {
            JsonNode node = mapper.readTree(row.getDetail());
            String reason = node.path("reason").asText("");
            return reason.isBlank() ? null : "[" + row.getStep()
                    + (row.getSubKey() == null ? "" : " " + row.getSubKey()) + "] " + reason;
        } catch (Exception e) {
            return row.getDetail();
        }
    }

    private GateReportVO latestGateReport(long chapterId) {
        GateReportDataService.LatestChapterReport row = gateReportData.findLatestChapterReport(chapterId);
        if (row == null) return null;
        try {
            JsonNode node = mapper.readTree(row.resultJson());
            List<java.util.Map<String, Object>> checks = mapper.convertValue(
                    node.path("checks"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {
                    });
            return new GateReportVO(GateType.MECHANICAL.wire(), row.passed(),
                    row.createTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    checks);
        } catch (Exception e) {
            throw new IllegalStateException("门禁报告不可解析", e);
        }
    }

    /** 最新 AI 审校报告 → VO（skipped 的报告也如实透出）。审校未跑过返回 null。 */
    public ReviewVO latestReview(long chapterId) {
        GateReportDataService.LatestReview row = gateReportData.findLatestChapterReview(chapterId);
        if (row == null) return null;
        String time = row.createTime().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            JsonNode node = mapper.readTree(row.resultJson());
            if (node.has("skipped")) {
                return new ReviewVO(true, "skipped", time, "审校输出解析失败，本轮跳过", List.of());
            }
            List<java.util.Map<String, Object>> issues = mapper.convertValue(
                    node.path("issues"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {
                    });
            String verdict = node.path("verdict").asText("");
            return new ReviewVO(!"blocker".equals(verdict), verdict, time,
                    node.path("summary").asText(""), issues);
        } catch (Exception e) {
            throw new IllegalStateException("审校报告不可解析", e);
        }
    }

    private LlmTotalsVO toTotalsVO(LlmCallLogDataService.Totals t) {
        return new LlmTotalsVO(t.calls(), t.promptTokens(), t.completionTokens(),
                t.totalTokens(), t.avgLatencyMs());
    }
}
