package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.model.enums.ForeshadowStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.RetroProposalDataService;
import com.zzdzz.novelgen.service.data.VolumeReviewDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.dto.ForeshadowDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.zzdzz.novelgen.service.StageLog.Phase.DONE;
import static com.zzdzz.novelgen.service.StageLog.Phase.START;

/**
 * 卷级复盘 Agent：读一卷的「规划 vs 实际产出」——卷纲行、各章事实账摘要、世界状态首末、伏笔账本——
 * 先做确定性机械对账（伏笔排期兑现、字数预算偏差、章节状态），再让 LLM 做叙事层漂移分析
 * （情节/人物/世界观/节奏），合并成报告落库（每卷保留最新一份）。
 * 机械对账不依赖模型；LLM 挂了报告仍含对账部分（fail-open）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VolumeReviewService {


    private final ChapterDataService chapterData;
    private final DigestDataService digestData;
    private final ForeshadowDataService foreshadowData;
    private final WorldStateDataService worldStateData;
    private final VolumeReviewDataService reviewDAO;
    private final RetroProposalDataService proposalData;
    private final LlmJson llmJson;
    private final StageLog stageLog;
    private final ObjectMapper mapper;
    private final PromptTemplateService promptTemplates;


    /** 复盘一卷（同步，约 1-3 分钟）：机械对账 + LLM 漂移分析，报告落库并返回。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> review(long novelId, int volNo) {
        List<ChapterDataService.VolumeFactRow> rows = chapterData.listVolumeFacts(novelId, volNo);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "卷 " + volNo + " 不存在或没有规划行");
        }
        int fromNo = rows.get(0).chapterNo();
        int toNo = rows.get(rows.size() - 1).chapterNo();
        stageLog.emit(novelId, StageLog.Stage.VOLUME_RETRO, START,
                Map.of("volNo", volNo, "from", fromNo, "to", toNo));

        Map<String, ForeshadowDTO> ledger = new HashMap<>();
        for (ForeshadowDTO f : foreshadowData.listByNovel(novelId)) ledger.put(f.getCode(), f);
        Map<String, Object> mechanical = mechanicalAudit(rows, ledger);

        Map<String, Object> review;
        try {
            review = llmReview(novelId, volNo, rows, mechanical, fromNo, toNo);
        } catch (Exception e) {
            log.warn("第 {} 卷复盘 LLM 分析失败，报告仅含机械对账：{}", volNo, e.getMessage());
            review = Map.of("overall", "unknown", "summary", "LLM 分析失败，仅含机械对账：" + e.getMessage(),
                    "drifts", List.of(), "highlights", List.of(), "next_volume", "");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("vol_no", volNo);
        report.put("from_no", fromNo);
        report.put("to_no", toNo);
        report.put("mechanical", mechanical);
        report.put("review", review);
        reviewDAO.upsert(novelId, volNo, mapper.valueToTree(report));
        extractProposals(novelId, volNo, review);
        stageLog.emit(novelId, StageLog.Stage.VOLUME_RETRO, DONE, Map.of("volNo", volNo));
        return report;
    }

    /** 流 D：复盘建议落 retro_proposals（同卷同内容去重；采纳状态人工决策）。 */
    @SuppressWarnings("unchecked")
    private void extractProposals(long novelId, int volNo, Map<String, Object> review) {
        try {
            Object driftsObj = review.get("drifts");
            if (driftsObj instanceof List<?> drifts) {
                for (Object d : drifts) {
                    String text;
                    if (d instanceof Map<?, ?> dm) {
                        // schema 字段是 where/issue/suggestion（见审校 prompt），缺正文的残缺项不落提案
                        String where = dm.get("where") == null ? "" : String.valueOf(dm.get("where"));
                        String issue = dm.get("issue") == null ? "" : String.valueOf(dm.get("issue"));
                        String suggestion = dm.get("suggestion") == null ? "" : String.valueOf(dm.get("suggestion"));
                        if (issue.isBlank()) continue;
                        text = "[" + dm.get("severity") + "/" + dm.get("type") + "] "
                                + (where.isBlank() ? "" : where + "：") + issue
                                + (suggestion.isBlank() ? "" : "（建议：" + suggestion + "）");
                    } else {
                        text = String.valueOf(d);
                    }
                    proposalData.propose(novelId, volNo, "REVIEW", text);
                }
            }
            Object next = review.get("next_volume");
            if (next != null && !String.valueOf(next).isBlank()) {
                proposalData.propose(novelId, volNo, "REVIEW", "下卷建议：" + next);
            }
        } catch (Exception e) {
            log.warn("复盘提案提取失败（不影响报告）：{}", e.getMessage());
        }
    }

    /** 上次报告；无则 null。 */
    public String findLatest(long novelId, int volNo) {
        return reviewDAO.findJson(novelId, volNo);
    }

    // ===== 机械对账（确定性，零 LLM） =====

    @SuppressWarnings("unchecked")
    private Map<String, Object> mechanicalAudit(List<ChapterDataService.VolumeFactRow> rows,
                                                Map<String, ForeshadowDTO> ledger) {
        List<Map<String, Object>> foreshadowAudit = new ArrayList<>();
        List<Map<String, Object>> budgetAudit = new ArrayList<>();
        Map<String, Integer> statusCount = new LinkedHashMap<>();
        int textLenTotal = 0;

        for (var row : rows) {
            int no = row.chapterNo();
            int len = (int) row.textLen();
            textLenTotal += len;
            statusCount.merge(row.status(), 1, Integer::sum);

            // 字数预算偏差
            int bMin = row.budgetMin();
            int bMax = row.budgetMax();
            boolean outOfBand = len > 0 && (len < bMin * 0.85 || len > bMax * 1.15);
            if (outOfBand || len == 0) {
                budgetAudit.add(Map.of("chapter_no", no, "budget", bMin + "-" + bMax,
                        "actual", len, "verdict", len == 0 ? "无正文" : "超出容差带"));
            }

            // 伏笔排期对账
            try {
                for (JsonNode codeNode : mapper.readTree(row.refs())) {
                    String code = codeNode.asText("");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chapter_no", no);
                    item.put("code", code);
                    ForeshadowDTO f = ledger.get(code);
                    if (f == null) {
                        item.put("verdict", "账本无此编码（异常）");
                    } else if (ForeshadowStatus.RECOVERED.is(f.getStatus())) {
                        item.put("verdict", "已回收 ✓（第" + f.getRecoveredIn() + "章）");
                    } else if (ForeshadowStatus.PLANTED.is(f.getStatus())) {
                        item.put("verdict", "已埋设，待回收（第" + f.getPlantedIn() + "章埋）");
                    } else {
                        item.put("verdict", "状态 " + f.getStatus() + "，排期未兑现");
                    }
                    foreshadowAudit.add(item);
                }
            } catch (Exception ignore) {
                // refs jsonb 损坏不拦对账
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chapters", rows.size());
        out.put("text_len_total", textLenTotal);
        out.put("status_count", statusCount);
        out.put("foreshadow_audit", foreshadowAudit);
        out.put("budget_outliers", budgetAudit);
        return out;
    }

    // ===== LLM 漂移分析 =====

    @SuppressWarnings("unchecked")
    private Map<String, Object> llmReview(long novelId, int volNo, List<ChapterDataService.VolumeFactRow> rows,
                                          Map<String, Object> mechanical, int fromNo, int toNo) {
        // 各章实际收束：该卷范围内的事实账摘要
        StringBuilder digestsSb = new StringBuilder();
        for (DigestDataService.DigestItem d : digestData.listByNovel(novelId)) {
            if (d.chapterNo() < fromNo || d.chapterNo() > toNo) continue;
            digestsSb.append("第").append(d.chapterNo()).append("章：")
                    .append(d.contentMd().replaceAll("\\s+", " ")).append('\n');
        }
        // 世界状态：卷首 vs 卷末
        List<WorldStateDataService.StateRow> states = worldStateData.listByNovel(novelId, 200).stream()
                .filter(s -> s.chapterNo() >= fromNo && s.chapterNo() <= toNo).toList();
        String worldFirst = states.isEmpty() ? "（无）" : states.get(states.size() - 1).stateJson();
        String worldLast = states.isEmpty() ? "（无）" : states.get(0).stateJson();

        StringBuilder rowsSb = new StringBuilder();
        for (var r : rows) {
            rowsSb.append("第").append(r.chapterNo()).append("章《").append(r.title())
                    .append("》目标：").append(r.goal())
                    .append("｜钩子：").append(r.hook())
                    .append("｜实际：").append(r.status()).append(' ').append(r.textLen()).append("字\n");
        }

        String user = promptTemplates.format(LlmNode.VOLUME_REVIEW, "user", """
                任务：复盘第 %d 卷（第 %d-%d 章）。你是资深网文责编，对照卷纲意图与实际成稿找漂移。

                【卷纲行 vs 实际】
                %s
                【各章事实账（实际发生了什么）】
                %s
                【世界状态：卷首】
                %s
                【世界状态：卷末】
                %s
                【机械对账（确定性结果，直接采信）】
                %s

                只输出 JSON：
                {"overall":"pass|drift|critical","summary":"300字内总评：主线推进/人物弧光/卷尾钩子兑现度",
                 "drifts":[{"type":"plot|foreshadow|character|world|pacing","severity":"minor|major",
                   "where":"第N章或全卷","issue":"漂移描述（对照卷纲意图）","suggestion":"怎么改"}],
                 "highlights":["做得好的点"],"next_volume":"下一卷建议（150字内）"}
                规则：
                - 机械对账已给出的伏笔/字数结论不要重复报，只在其揭示的模式上展开叙事层分析。
                - 漂移 = 实际走向偏离卷纲意图或前后矛盾；没有把握的不要报；字符串值内部禁止英文双引号。
                - 不要输出思考过程，只输出 JSON。
                """, volNo, fromNo, toNo, rowsSb,
                digestsSb.isEmpty() ? "（本卷无事实账）" : digestsSb.toString(),
                worldFirst, worldLast, mechanical);

        JsonNode n = llmJson.ask(new LlmPort.ChatRequest(
                        LlmNode.VOLUME_REVIEW, novelId, null,
                        List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.VOLUME_REVIEW, "system",
                                        "你是资深网文责编，负责卷级复盘。只输出合法 JSON，"
                                                + "字符串值内部禁止英文双引号，引用一律用「」。")),
                                LlmPort.Message.user(user)),
                        LlmTemps.VOLUME_REVIEW),
                node -> {
                    String overall = node.path("overall").asText("");
                    if (!List.of("pass", "drift", "critical").contains(overall)) {
                        throw new LlmJson.Bad("overall 非法: " + overall);
                    }
                    if (!node.path("drifts").isArray()) throw new LlmJson.Bad("drifts 必须是数组");
                    return node;
                }, 2);
        return mapper.convertValue(n, Map.class);
    }
}
