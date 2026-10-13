package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CalibrationMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.CalibrationDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO;
import com.zzdzz.novelgen.model.vo.derive.QcVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ⑧ 质检（v3.2 §⑧：脚本 7 项硬判 + LLM 快检 4 项 → qc_report）。
 *
 * <p>脚本项全硬判（grep/计数，不口头 PASS）；LLM 快检 fail-open 由脚本兜底。
 * 通过 → PASSED + calibration 回填 + work_fingerprint 登记（主角名+抽中弧 BRIDGE）
 * + novels 统计回填 + 拍实例 VERIFIED + 章 QC_PASS。
 * 不通过 → qc_round+1；&lt;2 回 REWORK（⑥b 重跑），≥2 FAILED。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveQcService {

    public static final String NODE_QC = "derive_qc";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /** 工作流痕迹正则（正文体；章注豁免） */
    static final Pattern FLOW_PATTERN = Pattern.compile(
            "O-P\\d|S-P\\d|W-P\\d|O-F\\d|素材池|素材来源|抽取稿|标签层|约束采样|域格|拍实例|拍素材|弧素材|beat_type|arc_fit|label_text|工作流");

    static final String QC_PROMPT = """
            你是小说质检员。输入含：域格、类别与目标节奏、结局形状、产品形态、
            主线、拍清单（seq/拍型/filled 情节点/归属章）、各章（章号/章题/章纲/正文）。
            只依据给出材料判断，四项各给 pass 与 note（note ≤40字）：
            1. fulfillment：每个非 SKIPPED 拍在对应章正文是否兑现（丢了哪些 seq 列出）；
            2. pacing：节奏是否符合目标（锚点 0.75/1.00 要求前 3 章内进冲突；0.00/0.25 允许铺垫）；
            3. voice：跨章人物语气/称谓是否一致；
            4. domain：事件是否在域格关系动作内成立、是否混入反域元素。
            输出 JSON 对象（只输出 JSON）：
            {"fulfillment":{"pass":true,"note":"…"},
             "pacing":{"pass":true,"note":"…"},
             "voice":{"pass":true,"note":"…"},
             "domain":{"pass":true,"note":"…"},
             "issues":["可执行的问题描述，没有则空数组"]}
            """;

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final WordlistMapper wordlistMapper;
    private final QcReportMapper qcReportMapper;
    private final CalibrationMapper calibrationMapper;
    private final WorkFingerprintMapper fingerprintMapper;
    private final AssetOutlineMapper outlineMapper;
    private final LabelIndexMapper labelIndexMapper;
    private final LlmJson llmJson;

    /** ⑧：跑一轮质检。 */
    public QcVO qc(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"QC".equals(run.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 状态应为 QC，当前 " + run.getStatus());
        }
        if (run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 未挂成品书");
        }
        long novelId = run.getNovelId();
        int roundNo = (run.getQcRound() == null ? 0 : run.getQcRound()) + 1;

        NovelDO novel = novelMapper.selectById(novelId);
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq));

        // ---- 脚本 7 项硬判（防复刻开关：关=词表类降报告，语义判定交 LLM domain 项）----
        boolean antiRepro = Boolean.TRUE.equals(run.getAntiRepro());
        List<CheckItem> script = new ArrayList<>();
        script.add(checkTotalWords(run, chapters));
        script.add(checkChapterBudgets(chapters));
        script.add(checkBeats(beats, chapters));
        script.add(checkBlacklist(run, chapters, antiRepro));
        script.add(checkCounterWords(run, chapters, antiRepro));
        script.add(checkCategoryConcentration(run, chapters));
        script.add(checkFingerprint(run, novel, chapters, antiRepro));

        boolean scriptPass = script.stream().allMatch(CheckItem::pass);

        // ---- LLM 快检 4 项（脚本全过才跑，省一轮钱；fail-open 由 issues 收口）----
        JsonNode llmResult = null;
        List<String> llmIssues = new ArrayList<>();
        boolean llmPass = true;
        if (scriptPass) {
            try {
                llmResult = llmCheck(run, novel, chapters, beats, llmIssues);
                // v3.2 口径：判定只看四项 pass；issues 数组是建议清单，记录但不参与判定
                llmPass = true;
                for (String key : List.of("fulfillment", "pacing", "voice", "domain")) {
                    if (!llmResult.path(key).path("pass").asBoolean(true)) {
                        llmPass = false;
                    }
                }
            } catch (Exception e) {
                log.warn("⑧LLM 快检异常（fail-open，记入 issues）：{}", e.getMessage());
                llmIssues.add("LLM 快检失败：" + e.getMessage());
                llmPass = false;
            }
        }

        // ---- 汇总问题清单 ----
        List<String> issues = new ArrayList<>();
        script.stream().filter(c -> !c.pass()).forEach(c -> issues.add(c.name() + "：" + c.detail()));
        issues.addAll(llmIssues);

        boolean pass = scriptPass && llmPass;
        int prevRound = run.getQcRound() == null ? 0 : run.getQcRound();
        String verdict;
        if (pass) {
            verdict = "PASS";
        } else if (prevRound < 2) {
            verdict = "REWORK";
        } else {
            verdict = "FAIL";
        }

        // ---- 落 qc_report ----
        ObjectNode scriptJson = STATIC_MAPPER.createObjectNode();
        for (CheckItem item : script) {
            ObjectNode n = scriptJson.putObject(item.name());
            n.put("pass", item.pass());
            n.put("detail", item.detail());
        }
        QcReportDO report = new QcReportDO();
        report.setRunId(runId);
        report.setRoundNo(roundNo);
        report.setScriptResult(scriptJson.toString());
        report.setLlmResult(llmResult == null ? null : llmResult.toString());
        report.setVerdict(verdict);
        report.setIssues(toJson(issues));
        // 修复记录：本轮是第几轮打回的延续（只记问题与动作，不存全文）
        report.setRepairLog(prevRound == 0 ? "[]"
                : toJson(Map.of("fromRound", prevRound, "issues", issues)));
        qcReportMapper.insertFull(report);

        // ---- 统计实测（无论判定先算，PASS 时回填）----
        int wordsActual = chapters.stream()
                .mapToInt(c -> c.getCharCount() == null ? 0 : c.getCharCount()).sum();
        int chapterAvg = chapters.isEmpty() ? 0 : wordsActual / chapters.size();
        int pActual = (int) beats.stream()
                .filter(b -> !"SKIPPED".equals(b.getStatus())).count();

        if ("PASS".equals(verdict)) {
            // calibration 回填
            CalibrationDO cal = calibrationMapper.selectOne(new LambdaQueryWrapper<CalibrationDO>()
                    .eq(CalibrationDO::getRunId, runId).last("LIMIT 1"));
            if (cal != null) {
                cal.setPActual(pActual);
                cal.setWordsActual(wordsActual);
                cal.setChapterAvgActual(chapterAvg);
                calibrationMapper.updateById(cal);
            }
            // work_fingerprint 登记：主角名 + 抽中弧 BRIDGE
            registerFingerprints(run, novel);
            // novels 统计回填
            novelMapper.updateStats(novelId, wordsActual, chapters.size());
            // 拍实例 VERIFIED、章 QC_PASS
            for (BeatInstanceDO b : beats) {
                if (!"SKIPPED".equals(b.getStatus())) {
                    b.setStatus("VERIFIED");
                    beatInstanceMapper.updateById(b);
                }
            }
            for (ChapterDO c : chapters) {
                c.setStatus("QC_PASS");
                chapterMapper.updateById(c);
            }
            runMapper.updateStatus(runId, "PASSED", roundNo);
        } else if ("REWORK".equals(verdict)) {
            runMapper.updateStatus(runId, "REWORK", roundNo);
        } else {
            runMapper.updateStatus(runId, "FAILED", roundNo);
        }

        int scriptPassedCount = (int) script.stream().filter(CheckItem::pass).count();
        log.info("⑧质检：runId={} round={} script={}/{} llm={} verdict={} issues={}",
                runId, roundNo, scriptPassedCount, script.size(),
                scriptPass ? (llmPass ? "4/4" : "fail") : "skip", verdict, issues.size());
        return new QcVO(runId, novelId, roundNo, verdict,
                scriptPassedCount, script.size(), scriptPass, llmPass,
                issues, wordsActual, chapterAvg, pActual);
    }

    // ================= 脚本 7 项 =================

    record CheckItem(String name, boolean pass, String detail) {
    }

    CheckItem checkTotalWords(RunDO run, List<ChapterDO> chapters) {
        int total = chapters.stream().mapToInt(c -> c.getCharCount() == null ? 0 : c.getCharCount()).sum();
        int lo = run.getParamWordsMin();
        int hi = run.getParamWordsMax();
        return new CheckItem("1总字数", total >= lo && total <= hi,
                "Σ char_count=" + total + " ∈ [" + lo + "," + hi + "]");
    }

    CheckItem checkChapterBudgets(List<ChapterDO> chapters) {
        List<String> bad = new ArrayList<>();
        for (ChapterDO c : chapters) {
            int budget = c.getBudget() == null ? 0 : c.getBudget();
            int n = c.getCharCount() == null ? 0 : c.getCharCount();
            int lo = (int) Math.floor(budget * 0.9);
            int hi = (int) Math.ceil(budget * 1.1);
            if (n < lo || n > hi) {
                bad.add("ch" + c.getChapterNo() + "=" + n + "（预算" + budget + "带[" + lo + "," + hi + "]）");
            }
        }
        return new CheckItem("2每章字数", bad.isEmpty(),
                bad.isEmpty() ? chapters.size() + " 章全部 ±10% 内" : "超差：" + String.join("；", bad));
    }

    CheckItem checkBeats(List<BeatInstanceDO> beats, List<ChapterDO> chapters) {
        List<BeatInstanceDO> active = beats.stream()
                .filter(b -> !"SKIPPED".equals(b.getStatus())).toList();
        // 章注挂拍 seq 集合
        Map<Integer, Set<Integer>> commentSeqs = new LinkedHashMap<>();
        Pattern p = DeriveWriteService.COMMENT_PATTERN;
        for (ChapterDO c : chapters) {
            Set<Integer> seqs = new LinkedHashSet<>();
            if (c.getComment() != null) {
                Matcher m = p.matcher(c.getComment());
                if (m.find()) {
                    for (String s : m.group(2).split("[,，\\s]+")) {
                        if (!s.isBlank()) {
                            seqs.add(Integer.parseInt(s.trim()));
                        }
                    }
                }
            }
            commentSeqs.put(c.getChapterNo(), seqs);
        }
        List<String> problems = new ArrayList<>();
        // 每个 active 拍：章注里恰好出现一次，且在自己章
        Map<Integer, Integer> seqCount = new HashMap<>();
        Map<Integer, Integer> seqChapter = new HashMap<>();
        commentSeqs.forEach((chNo, seqs) -> seqs.forEach(s -> {
            seqCount.merge(s, 1, Integer::sum);
            seqChapter.putIfAbsent(s, chNo);
        }));
        for (BeatInstanceDO b : active) {
            int seq = b.getSeq();
            int cnt = seqCount.getOrDefault(seq, 0);
            if (cnt != 1) {
                problems.add("seq" + seq + " 章注出现 " + cnt + " 次（应 1）");
            } else if (!seqChapter.get(seq).equals(b.getChapterNo())) {
                problems.add("seq" + seq + " 章注归属 ch" + seqChapter.get(seq) + " ≠ 计划 ch" + b.getChapterNo());
            }
        }
        // 章注里不应有计划外 seq
        Set<Integer> planSeqs = active.stream().map(BeatInstanceDO::getSeq).collect(Collectors.toSet());
        for (Map.Entry<Integer, Set<Integer>> e : commentSeqs.entrySet()) {
            for (int s : e.getValue()) {
                if (!planSeqs.contains(s)) {
                    problems.add("ch" + e.getKey() + " 章注含计划外 seq" + s);
                }
            }
        }
        return new CheckItem("3拍兑现", problems.isEmpty(),
                problems.isEmpty()
                        ? active.size() + " 拍全部恰好一次且章归属一致"
                        : String.join("；", problems));
    }

    CheckItem checkBlacklist(RunDO run, List<ChapterDO> chapters, boolean antiRepro) {
        String full = joinContent(chapters);
        // 编号/工作流痕迹 regex：工程卫生，恒硬判（与防复刻开关无关）
        Matcher fm = FLOW_PATTERN.matcher(full);
        List<String> flow = new ArrayList<>();
        while (fm.find()) {
            flow.add(fm.group());
        }
        List<String> hits = List.of();
        boolean pass = flow.isEmpty();
        if (antiRepro) {
            // 词表黑名单（真实作品名/血缘词面）：防复刻原口径才硬判
            hits = wordlistMapper.selectList(new LambdaQueryWrapper<WordlistDO>()
                            .eq(WordlistDO::getKind, "BLACKLIST"))
                    .stream().map(WordlistDO::getWord).filter(w -> w != null && !w.isBlank())
                    .filter(full::contains).toList();
            pass = pass && hits.isEmpty();
        }
        String detail = "流程痕迹=" + (flow.isEmpty() ? 0 : flow)
                + "；BLACKLIST=" + (antiRepro ? (hits.isEmpty() ? 0 : hits) : "降报告（anti_repro=关）");
        return new CheckItem("4黑名单/痕迹", pass, detail);
    }

    CheckItem checkCounterWords(RunDO run, List<ChapterDO> chapters, boolean antiRepro) {
        // domain_snapshot.counterWords 是 PG 数组字面量字符串 "{a,b,c}"
        List<String> counters = parsePgArray(run.getDomainSnapshot(), "counterWords");
        String full = joinContent(chapters);
        List<String> hits = counters.stream().filter(full::contains).toList();
        if (!antiRepro) {
            // 反域词多为常用词（正面/公开/空旷），字面 grep 误伤高——关=只报告，语义判定在 LLM domain 项
            return new CheckItem("5反域词", true,
                    "报告（anti_repro=关，语义判定交 LLM domain 项）：命中=" + (hits.isEmpty() ? 0 : hits));
        }
        return new CheckItem("5反域词", hits.isEmpty(),
                "counterWords " + counters.size() + " 词命中=" + (hits.isEmpty() ? 0 : hits));
    }

    CheckItem checkCategoryConcentration(RunDO run, List<ChapterDO> chapters) {
        Map<String, List<String>> termsByCat = new LinkedHashMap<>();
        for (WordlistDO w : wordlistMapper.selectList(new LambdaQueryWrapper<WordlistDO>()
                .eq(WordlistDO::getKind, "CATEGORY_TERM"))) {
            if (w.getCategory() != null && w.getWord() != null) {
                termsByCat.computeIfAbsent(w.getCategory(), k -> new ArrayList<>()).add(w.getWord());
            }
        }
        if (termsByCat.isEmpty()) {
            // CATEGORY_TERM 词表未建 → 降级报告（不拦，⑧ 报告注明）
            return new CheckItem("6类别浓度", true,
                    "降级：wordlist 无 CATEGORY_TERM 行，跳过硬判（待词表补齐后启用）");
        }
        List<String> weak = new ArrayList<>();
        for (ChapterDO c : chapters) {
            String body = c.getContent() == null ? "" : c.getContent();
            int sum = termsByCat.values().stream()
                    .mapToInt(ws -> ws.stream().mapToInt(body::indexOf).filter(i -> i >= 0).sum())
                    .sum();
            if (sum < 3) {
                weak.add("ch" + c.getChapterNo() + "=" + sum);
            }
        }
        return new CheckItem("6类别浓度", weak.isEmpty(),
                weak.isEmpty() ? "每章类别词 ≥3" : "低于 3 的章：" + weak);
    }

    CheckItem checkFingerprint(RunDO run, NovelDO novel, List<ChapterDO> chapters, boolean antiRepro) {
        List<String> problems = new ArrayList<>();
        // 章数：有正文的章必须 ≥1，且全部成稿（工程卫生，恒判）
        long drafted = chapters.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank()).count();
        if (drafted != chapters.size() || chapters.isEmpty()) {
            problems.add("章数不齐：有正文 " + drafted + "/" + chapters.size());
        }
        // 主角名出现（称谓锚点信号，恒判）；历史重名查重仅防复刻开时拦
        String protagonist = readProtagonist(novel);
        if (protagonist == null || protagonist.isBlank()) {
            problems.add("story_assets 缺 protagonist（⑥b 未落库？）");
        } else {
            String full = joinContent(chapters);
            if (!full.contains(protagonist)) {
                problems.add("正文未出现主角名「" + protagonist + "」");
            }
            if (antiRepro) {
                long dup = fingerprintMapper.selectCount(new LambdaQueryWrapper<WorkFingerprintDO>()
                        .eq(WorkFingerprintDO::getKind, "PROTAGONIST_NAME")
                        .eq(WorkFingerprintDO::getValue, protagonist));
                if (dup > 0) {
                    problems.add("主角名「" + protagonist + "」与历史 work_fingerprint 重复");
                }
            }
        }
        String detail = problems.isEmpty()
                ? "章数=" + chapters.size() + "；主角名=" + protagonist
                    + (antiRepro ? " 无历史重复" : "（历史重名查重随 anti_repro=关跳过）")
                : String.join("；", problems);
        return new CheckItem("7结构指纹", problems.isEmpty(), detail);
    }

    // ================= LLM 快检 =================

    JsonNode llmCheck(RunDO run, NovelDO novel, List<ChapterDO> chapters,
                      List<BeatInstanceDO> beats, List<String> issues) {
        String user = buildQcUser(run, novel, chapters, beats);
        return llmJson.ask(
                new LlmPort.ChatRequest(NODE_QC, run.getId(), novel.getId(),
                        List.of(LlmPort.Message.system(QC_PROMPT), LlmPort.Message.user(user)),
                        0.2),
                root -> parseQc(root, issues), 2);
    }

    JsonNode parseQc(JsonNode root, List<String> issues) {
        for (String key : List.of("fulfillment", "pacing", "voice", "domain")) {
            JsonNode n = root.path(key);
            if (!n.has("pass")) {
                throw new LlmJson.Bad("缺 " + key + ".pass");
            }
            if (!n.path("pass").asBoolean()) {
                issues.add(key + "：" + n.path("note").asText("未通过"));
            }
        }
        root.path("issues").forEach(i -> issues.add(i.asText()));
        return root;
    }

    String buildQcUser(RunDO run, NovelDO novel, List<ChapterDO> chapters, List<BeatInstanceDO> beats) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("作品名", novel.getTitle());
        root.put("主角", readProtagonist(novel));
        root.set("域", DeriveWriteService.readTreeSafe(run.getDomainSnapshot()));
        root.set("类别", DeriveWriteService.readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        var bArr = root.putArray("拍清单");
        for (BeatInstanceDO b : beats) {
            var n = bArr.addObject();
            n.put("seq", b.getSeq());
            n.put("beat_type", b.getBeatType());
            n.put("chapter", b.getChapterNo());
            n.put("status", b.getStatus());
            n.put("filled", b.getFilledText() == null ? "" : b.getFilledText());
        }
        var cArr = root.putArray("各章");
        for (ChapterDO c : chapters) {
            var n = cArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("outline", c.getDraftOutline() == null ? "" : c.getDraftOutline());
            n.put("body", c.getContent() == null ? "" : c.getContent());
        }
        return root.toString();
    }

    // ================= PASS 后登记 =================

    void registerFingerprints(RunDO run, NovelDO novel) {
        String protagonist = readProtagonist(novel);
        if (protagonist != null && !protagonist.isBlank()) {
            insertFp(novel.getId(), "PROTAGONIST_NAME", protagonist, null);
        }
        // 抽中弧 BRIDGE（fingerprint = md5(funcTag|cat签名)，与⑤同构判定同口径）
        for (long outlineId : parseIdArray(run.getPickedOutlineIds())) {
            AssetOutlineDO arc = outlineMapper.selectById(outlineId);
            if (arc == null) {
                continue;
            }
            LabelIndexDO label = labelIndexMapper.selectOne(new LambdaQueryWrapper<LabelIndexDO>()
                    .eq(LabelIndexDO::getEntryType, "OUTLINE")
                    .eq(LabelIndexDO::getEntryTypeId, outlineId)
                    .last("LIMIT 1"));
            String fp = label == null ? null : fingerprintOf(arc.getFuncTag(), label.getCat());
            String value = arc.getCode() != null ? arc.getCode()
                    : (arc.getLabelText() == null ? "" : arc.getLabelText().substring(0, Math.min(32, arc.getLabelText().length())));
            insertFp(novel.getId(), "BRIDGE", value, fp);
        }
    }

    void insertFp(long novelId, String kind, String value, String fingerprint) {
        try {
            WorkFingerprintDO fp = new WorkFingerprintDO();
            fp.setNovelId(novelId);
            fp.setKind(kind);
            fp.setValue(value);
            fp.setFingerprint(fingerprint);
            fingerprintMapper.insert(fp);
        } catch (Exception e) {
            // 唯一键 (novel_id, kind, value) 撞了=幂等，忽略
            log.debug("work_fingerprint 已存在：{} {}", kind, value);
        }
    }

    /** 与 DeriveSampleService.fingerprintOf 同口径（md5(funcTag|cat 一位小数签名)）。 */
    static String fingerprintOf(String funcTag, String catJson) {
        StringBuilder sig = new StringBuilder();
        try {
            JsonNode n = STATIC_MAPPER.readTree(catJson == null || catJson.isBlank() ? "{}" : catJson);
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            n.fields().forEachRemaining(fields::add);
            fields.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, JsonNode> e : fields) {
                sig.append(e.getKey()).append('=')
                        .append(BigDecimal.valueOf(e.getValue().asDouble()).setScale(1, RoundingMode.HALF_UP))
                        .append(';');
            }
        } catch (Exception ignored) {
            // 坏 cat 视为空
        }
        return md5((funcTag == null ? "" : funcTag) + "|" + sig);
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================= 工具 =================

    static String toJson(Object o) {
        try {
            return STATIC_MAPPER.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String joinContent(List<ChapterDO> chapters) {
        return chapters.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .collect(Collectors.joining("\n"));
    }

    static String readProtagonist(NovelDO novel) {
        try {
            JsonNode n = STATIC_MAPPER.readTree(novel.getStoryAssets() == null || novel.getStoryAssets().isBlank()
                    ? "{}" : novel.getStoryAssets());
            return n.path("protagonist").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** domain_snapshot 里 PG 数组字面量字符串 "{a,b,c}" → List。 */
    static List<String> parsePgArray(String snapshotJson, String key) {
        try {
            JsonNode n = STATIC_MAPPER.readTree(snapshotJson == null || snapshotJson.isBlank() ? "{}" : snapshotJson);
            String raw = n.path(key).asText("");
            if (raw.isBlank()) {
                return List.of();
            }
            String inner = raw.replaceAll("^\\{|\\}$", "");
            List<String> out = new ArrayList<>();
            for (String s : inner.split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<Long> parseIdArray(String jsonArray) {
        try {
            JsonNode n = STATIC_MAPPER.readTree(jsonArray == null || jsonArray.isBlank() ? "[]" : jsonArray);
            List<Long> out = new ArrayList<>();
            n.forEach(x -> out.add(x.asLong()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
