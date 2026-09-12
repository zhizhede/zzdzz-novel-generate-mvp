package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.DigestDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.VolumeReviewDAO;
import com.zzdzz.novelgen.dao.WorldStateDAO;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class VolumeReviewService {

    private static final Logger log = LoggerFactory.getLogger(VolumeReviewService.class);

    private final ChapterDAO chapterDAO;
    private final DigestDAO digestDAO;
    private final ForeshadowDAO foreshadowDAO;
    private final WorldStateDAO worldStateDAO;
    private final VolumeReviewDAO reviewDAO;
    private final LlmJson llmJson;
    private final StageLog stageLog;
    private final ObjectMapper mapper;

    public VolumeReviewService(ChapterDAO chapterDAO, DigestDAO digestDAO,
                               ForeshadowDAO foreshadowDAO, WorldStateDAO worldStateDAO,
                               VolumeReviewDAO reviewDAO, LlmJson llmJson,
                               StageLog stageLog, ObjectMapper mapper) {
        this.chapterDAO = chapterDAO;
        this.digestDAO = digestDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.worldStateDAO = worldStateDAO;
        this.reviewDAO = reviewDAO;
        this.llmJson = llmJson;
        this.stageLog = stageLog;
        this.mapper = mapper;
    }

    /** 复盘一卷（同步，约 1-3 分钟）：机械对账 + LLM 漂移分析，报告落库并返回。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> review(long novelId, int volNo) {
        List<Map<String, Object>> rows = chapterDAO.listVolumeFacts(novelId, volNo);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "卷 " + volNo + " 不存在或没有规划行");
        }
        int fromNo = ((Number) rows.get(0).get("chapter_no")).intValue();
        int toNo = ((Number) rows.get(rows.size() - 1).get("chapter_no")).intValue();
        stageLog.emit(novelId, StageLog.Stage.VOLUME_RETRO, START,
                Map.of("volNo", volNo, "from", fromNo, "to", toNo));

        Map<String, ForeshadowDO> ledger = new HashMap<>();
        for (ForeshadowDO f : foreshadowDAO.listByNovel(novelId)) ledger.put(f.code(), f);
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
        stageLog.emit(novelId, StageLog.Stage.VOLUME_RETRO, DONE, Map.of("volNo", volNo));
        return report;
    }

    /** 上次报告；无则 null。 */
    public String findLatest(long novelId, int volNo) {
        return reviewDAO.findJson(novelId, volNo);
    }

    // ===== 机械对账（确定性，零 LLM） =====

    @SuppressWarnings("unchecked")
    private Map<String, Object> mechanicalAudit(List<Map<String, Object>> rows,
                                                Map<String, ForeshadowDO> ledger) {
        List<Map<String, Object>> foreshadowAudit = new ArrayList<>();
        List<Map<String, Object>> budgetAudit = new ArrayList<>();
        Map<String, Integer> statusCount = new LinkedHashMap<>();
        int textLenTotal = 0;

        for (Map<String, Object> row : rows) {
            int no = ((Number) row.get("chapter_no")).intValue();
            int len = ((Number) row.get("text_len")).intValue();
            textLenTotal += len;
            statusCount.merge(String.valueOf(row.get("status")), 1, Integer::sum);

            // 字数预算偏差
            int bMin = ((Number) row.get("budget_min")).intValue();
            int bMax = ((Number) row.get("budget_max")).intValue();
            boolean outOfBand = len > 0 && (len < bMin * 0.85 || len > bMax * 1.15);
            if (outOfBand || len == 0) {
                budgetAudit.add(Map.of("chapter_no", no, "budget", bMin + "-" + bMax,
                        "actual", len, "verdict", len == 0 ? "无正文" : "超出容差带"));
            }

            // 伏笔排期对账
            try {
                for (JsonNode codeNode : mapper.readTree(String.valueOf(row.get("refs")))) {
                    String code = codeNode.asText("");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chapter_no", no);
                    item.put("code", code);
                    ForeshadowDO f = ledger.get(code);
                    if (f == null) {
                        item.put("verdict", "账本无此编码（异常）");
                    } else if ("recovered".equals(f.status())) {
                        item.put("verdict", "已回收 ✓（第" + f.recoveredIn() + "章）");
                    } else if ("planted".equals(f.status())) {
                        item.put("verdict", "已埋设，待回收（第" + f.plantedIn() + "章埋）");
                    } else {
                        item.put("verdict", "状态 " + f.status() + "，排期未兑现");
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
    private Map<String, Object> llmReview(long novelId, int volNo, List<Map<String, Object>> rows,
                                          Map<String, Object> mechanical, int fromNo, int toNo) {
        // 各章实际收束：该卷范围内的事实账摘要
        StringBuilder digestsSb = new StringBuilder();
        for (DigestDAO.DigestItem d : digestDAO.listByNovel(novelId)) {
            if (d.chapterNo() < fromNo || d.chapterNo() > toNo) continue;
            digestsSb.append("第").append(d.chapterNo()).append("章：")
                    .append(d.contentMd().replaceAll("\\s+", " ")).append('\n');
        }
        // 世界状态：卷首 vs 卷末
        List<WorldStateDAO.StateRow> states = worldStateDAO.listByNovel(novelId, 200).stream()
                .filter(s -> s.chapterNo() >= fromNo && s.chapterNo() <= toNo).toList();
        String worldFirst = states.isEmpty() ? "（无）" : states.get(states.size() - 1).stateJson();
        String worldLast = states.isEmpty() ? "（无）" : states.get(0).stateJson();

        StringBuilder rowsSb = new StringBuilder();
        for (Map<String, Object> r : rows) {
            rowsSb.append("第").append(r.get("chapter_no")).append("章《").append(r.get("title"))
                    .append("》目标：").append(r.get("goal"))
                    .append("｜钩子：").append(r.get("hook"))
                    .append("｜实际：").append(r.get("status")).append(' ').append(r.get("text_len")).append("字\n");
        }

        String user = """
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
                """.formatted(volNo, fromNo, toNo, rowsSb,
                digestsSb.isEmpty() ? "（本卷无事实账）" : digestsSb.toString(),
                worldFirst, worldLast, mechanical);

        JsonNode n = llmJson.ask(new LlmPort.ChatRequest(
                        LlmNode.VOLUME_REVIEW, novelId, null,
                        List.of(LlmPort.Message.system("你是资深网文责编，负责卷级复盘。只输出合法 JSON，"
                                        + "字符串值内部禁止英文双引号，引用一律用「」。"),
                                LlmPort.Message.user(user)),
                        0.3),
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
