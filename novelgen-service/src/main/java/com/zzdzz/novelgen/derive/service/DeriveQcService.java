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
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationEvidenceMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.GenreProfileRegistry;
import com.zzdzz.novelgen.derive.genre.GenreQcAuditor;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.derive.genre.RomanceProfile;
import com.zzdzz.novelgen.derive.genre.GenreContract;
import com.zzdzz.novelgen.derive.genre.MysteryQcAuditor;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryKernelAssembler;
import com.zzdzz.novelgen.derive.story.StoryObligationEvidence;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.CalibrationDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationEvidenceDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO;
import com.zzdzz.novelgen.model.entity.derive.WorldStateDO;
import com.zzdzz.novelgen.model.vo.derive.QcVO;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * ⑧ 质检（v3.4：脚本 7 项硬判 + LLM 快检 5 项 → qc_report）。
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
            "O-P\\d|S-P\\d|W-P\\d|O-F\\d|素材池|素材来源|抽取稿|标签层|约束采样|域格|拍实例|拍素材|弧素材|beat_type|arc_fit|label_text|工作流"
            + "|状态包|上一稿|请提供第[零一二三四五六七八九十百千两\\d]+章");

    static final String QC_PROMPT = """
            你是小说质检员。输入含：域格、类别与目标节奏、结局形状、产品形态、主线、
            拍清单（seq/拍型/filled 情节点/归属章）、各章（章号/章题/章纲/正文）、
            人物人设卡、真相卡（若有）、世界观前提（若有）、题材意图（若有）。
            只依据给出材料判断，五项各给 pass 与 note（note ≤40字）：
            1. fulfillment：每个非 SKIPPED 拍在对应章正文是否兑现（丢了哪些 seq 列出）；
            2. pacing：节奏是否符合目标（锚点 0.75/1.00 要求前 3 章内进冲突；0.00/0.25 允许铺垫）；
            3. voice：①跨章人物语气/称谓是否一致；②每个出场角色本章行为能否由其 persona
               行为准则与欲望推出——推不出的点名（角色X做Y与其人设Z相悖）；persona 缺失的
               旧角色只查①并在 note 注明「无人设卡」；③文风贴合（若输入带『文风卡』或『目标文风』：
               句长/叙事密度/成语文言度/意象系的主要偏离点名；无卡跳过并注明「无文风基准」）；
            4. domain：事件是否在域格关系动作内成立、是否混入反域元素；若输入带『题材意图』，
               以意图的人物设定与剧情走向为预期基准——意图内的合理细节与人物不因超出素材域单独判负，
               只判真冲突（反域元素、与意图事实矛盾）；若输入带『世界观前提』，人名/器物/礼俗/称谓
               必须符合该世界观体系，混入异文化元素判 fail——**成语堆砌、文言腔、诗词化旁白**
               同计文化取向违例；
            5. logic：跨章一致与推理自洽——①关键数字/时间戳前后一致（金额、时刻、人数）；
               ②证据与结论挂钩（定性须有物证或已落实证言支撑，纯推测链不得作结案根据）；
               ③线索公平（谜面核心信息须在揭示前给到读者，不得用"没打听到"式省略堵死诡计）；
               ④若输入带『真相卡』：按卡硬对账——reveal_chapter 及其后 1~2 章必须出现
               solution 三要素（culprit/motive/method）的明确兑现动作（收押/判决/还原陈述/
               尸体发现这类可查验事件），缺任何一项 → logic pass=false，note 点名缺哪项；
               solution 专名与 timeline_anchors 数字必须在结局前找到对应事件，悬空 >1 处
               → pass=false 并在 issues 逐条点名；「案情已闭合 + 判决后情绪余韵尾」判 pass
               （余韵合法，悬空不合法）；无真相卡时按①②③判；
               ⑤剧透检查：若带 truth.reveal_chapter——reveal 之前的章节出现 solution 内容或
               暗示性写法（引号反讽、旁白剧透、角色提前知情）判 fail；
               ⑥世界状态账对照（若输入带『世界状态账』时间线）：a. 各章正文的时刻/地点/在场
               人物与账本及前后章不得矛盾；b. 账本 open_loops 已关闭的事件（如已脱困、已找到）
               不得在后续章再演一遍——同一脱困/同一已结束事件重复叙述直接 fail 并点名章号；
               c. key_facts 不得被后续正文推翻；发现矛盾 → logic pass=false，note 写「状态账冲突」，
               issues 逐条写清「第X章 vs 第Y章/账本：…」。
            输出 JSON 对象（只输出 JSON）：
            {"fulfillment":{"pass":true,"note":"…"},
             "pacing":{"pass":true,"note":"…"},
             "voice":{"pass":true,"note":"…"},
             "domain":{"pass":true,"note":"…"},
             "logic":{"pass":true,"note":"…"},
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
    private final WorldStateMapper worldStateMapper;
    private final LlmJson llmJson;
    @Autowired(required = false)
    private DerivePlanValidationService planValidationService;
    @Autowired(required = false)
    private DeriveStoryObligationMapper obligationMapper;
    @Autowired(required = false)
    private DeriveStoryObligationEvidenceMapper evidenceMapper;
    @Autowired(required = false)
    private DeriveContractService contractService;
    @Autowired(required = false)
    private GenreProfileRegistry genreProfileRegistry;
    @Autowired(required = false)
    private GenreQcAuditor genreQcAuditor;
    @Autowired(required = false)
    private MysteryQcAuditor mysteryQcAuditor;

    /** ⑧：跑一轮严格质检。 */
    public QcVO qc(long runId) {
        return qc(runId, false, false);
    }

    /**
     * ⑧：跑一轮质检。skipLlm=只跳 AI 五项快检，脚本硬判、qc_report 落档、
     * 章升 QC_PASS 与回炉熔断照常。
     */
    public QcVO qc(long runId, boolean skipLlm) {
        return qc(runId, skipLlm, false);
    }

    /**
     * ⑧：直通初稿质检（2026-11-01 用户定调 A）。
     *
     * <p>与 {@link #qc(long, boolean)} 的区别：脚本、计划、类型问题全部降级为报告，
     * 只要 run 有成品书就允许落 PASSED；qc_report 仍完整保存 script_result/issues，
     * 供人工终稿前处理。该口径只给 direct=true 的初稿直通使用，严格链路不走这里。
     */
    public QcVO qcDraft(long runId) {
        return qc(runId, true, true);
    }

    private QcVO qc(long runId, boolean skipLlm, boolean draftOnly) {
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
        script.add(checkChapterBudgets(run, chapters));
        script.add(checkBeats(beats, chapters));
        script.add(checkBlacklist(run, chapters, antiRepro));
        script.add(checkCounterWords(run, chapters, antiRepro));
        script.add(checkCategoryConcentration(run, chapters));
        script.add(checkFingerprint(run, novel, chapters, antiRepro));

        boolean scriptPass = script.stream().allMatch(CheckItem::pass);
        PlanValidationVO planCheck = planValidationService == null
                ? null : planValidationService.validateBook(runId);
        boolean planPass = planCheck == null || planCheck.passed();

        // ---- LLM 快检 5 项（修4，2026-10-16：script 未过也跑——原「全过才跑」让 run10 三轮
        //      REWORK 的 logic⑤ 剧透检查全程不可达；判定口径不变：pass = scriptPass && llmPass，
        //      失败轮多跑一次只为 issues 带语义方向 + 剧透兑底可达；异常仍 fail-open）----
        JsonNode llmResult = null;
        List<String> llmIssues = new ArrayList<>();
        boolean llmPass = true;
        if (skipLlm) {
            llmIssues.add("直通模式：跳过 AI 快检五项（脚本硬判照跑）");
        } else {
            try {
                List<WorldStateDO> ledger = worldStateMapper == null ? List.of()
                        : worldStateMapper.selectList(new LambdaQueryWrapper<WorldStateDO>()
                                .eq(WorldStateDO::getNovelId, novelId)
                                .orderByAsc(WorldStateDO::getChapterNo));
                llmResult = llmCheck(run, novel, chapters, beats, llmIssues, ledger);
                // v3.4 口径：判定只看五项 pass；issues 数组是建议清单，记录但不参与判定
                llmPass = true;
                for (String key : List.of("fulfillment", "pacing", "voice", "domain", "logic")) {
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
        issues.addAll(planIssueMessages(planCheck));
        List<String> genreIssues = genreQcIssues(run, novel, chapters);
        issues.addAll(genreIssues);

        boolean qualityPass = scriptPass && llmPass && planPass && genreIssues.isEmpty();
        boolean pass = draftOnly || qualityPass;
        int prevRound = run.getQcRound() == null ? 0 : run.getQcRound();
        String verdict;
        if (pass) {
            verdict = "PASS";
        } else if (prevRound < 2) {
            verdict = "REWORK";
        } else {
            verdict = "FAIL";
        }
        String repairTarget = pass ? "NONE"
                : (planPass ? "DRAFT" : DeriveGateService.repairTarget(planCheck));
        if (draftOnly && !issues.isEmpty()) {
            issues.add(0, "直通初稿：以下问题降级为报告，不阻断本次交付；人工终稿前必须处理");
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
                    beatInstanceMapper.updateStatus(b.getId(), b.getStatus());
                }
            }
            for (ChapterDO c : chapters) {
                c.setStatus("QC_PASS");
                chapterMapper.updateStatus(c.getId(), c.getStatus());
            }
            runMapper.updateStatus(runId, "PASSED", roundNo);
        } else if ("REWORK".equals(verdict)) {
            runMapper.updateStepStatus(runId, withQcRepairTarget(run.getStepStatus(), repairTarget));
            runMapper.updateStatus(runId, "REWORK", roundNo);
        } else {
            runMapper.updateStatus(runId, "FAILED", roundNo);
        }

        int scriptPassedCount = (int) script.stream().filter(CheckItem::pass).count();
        log.info("⑧质检：runId={} round={} script={}/{} llm={} verdict={} issues={}",
                runId, roundNo, scriptPassedCount, script.size(),
                skipLlm ? "直通跳过" : (scriptPass ? (llmPass ? "5/5" : "fail") : "skip"),
                verdict, issues.size());
        return new QcVO(runId, novelId, roundNo, verdict,
                scriptPassedCount, script.size(), !skipLlm, llmPass,
                issues, wordsActual, chapterAvg, pActual, repairTarget);
    }

    static List<String> planIssueMessages(PlanValidationVO validation) {
        if (validation == null || validation.passed()) {
            return List.of();
        }
        return validation.hardErrors().stream()
                .map(e -> e.code() + "：" + e.message()).toList();
    }

    static String repairTarget(String stepStatus) {
        return DeriveWriteService.readTreeSafe(stepStatus)
                .path("steps").path("QC_REPAIR_TARGET").asText("NONE");
    }

    static String withQcRepairTarget(String stepStatus, String repairTarget) {
        ObjectNode root = (ObjectNode) DeriveWriteService.readTreeSafe(stepStatus).deepCopy();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        steps.put("QC_REPAIR_TARGET", repairTarget == null ? "NONE" : repairTarget);
        return root.toString();
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

    /** 每章字数带（V115 len_check，默认关=降报告不判负；与⑦ validate 同开关同口径）。 */
    CheckItem checkChapterBudgets(RunDO run, List<ChapterDO> chapters) {
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
        boolean lenCheck = run != null && Boolean.TRUE.equals(run.getLenCheck());
        if (!lenCheck) {
            return new CheckItem("2每章字数", true,
                    "报告（len_check=关，不判负）：超差 " + bad.size() + " 章"
                            + (bad.isEmpty() ? "" : "：" + String.join("；", bad)));
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
            // 词表黑名单（真实作品名/血缘词面）：防复刻原口径才硬判；grp='MOTIF' 是大纲层母题
            // （P6 立项闸判据），不进正文 grep——语义比对在 derive_gate 提示词里做
            hits = wordlistMapper.selectList(new LambdaQueryWrapper<WordlistDO>()
                            .eq(WordlistDO::getKind, "BLACKLIST")
                            .and(w -> w.isNull(WordlistDO::getGrp).or().ne(WordlistDO::getGrp, "MOTIF")))
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
        // 修3（2026-10-16）：「每章≥3」三连误伤（run6/10/11；run12 优化后仍 10/12 章不达标）——
        // 日常/慢热章结构性 0 命中是体裁常态（案件词集中于案件章）。改**全书级**：
        // Σ全书 ≥ ceil(章数/2) 硬判（防整本跑题）；单章读数仅记录不判负，跑题语义交⑧ LLM domain/fulfillment。
        Map<Integer, Integer> hitsByCh = new LinkedHashMap<>();
        int total = 0;
        for (ChapterDO c : chapters) {
            String body = c.getContent() == null ? "" : c.getContent();
            int sum = termsByCat.values().stream()
                    .mapToInt(ws -> ws.stream().mapToInt(body::indexOf).filter(i -> i >= 0).sum())
                    .sum();
            hitsByCh.put(c.getChapterNo(), sum);
            total += sum;
        }
        int need = (chapters.size() + 1) / 2;
        boolean pass = total >= need;
        List<String> zero = hitsByCh.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(e -> "ch" + e.getKey()).toList();
        String detail = "全书Σ=" + total + "（需≥" + need + "＝ceil(章数/2)）；单章读数仅记录；0词章："
                + (zero.isEmpty() ? "无" : String.join(",", zero))
                + hitsByCh.entrySet().stream()
                        .filter(e -> e.getValue() > 0)
                        .map(e -> "；ch" + e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining());
        return new CheckItem("6类别浓度", pass, detail);
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
        return llmCheck(run, novel, chapters, beats, issues, List.of());
    }

    JsonNode llmCheck(RunDO run, NovelDO novel, List<ChapterDO> chapters,
                      List<BeatInstanceDO> beats, List<String> issues,
                      List<WorldStateDO> ledger) {
        String user = buildQcUser(run, novel, chapters, beats, ledger);
        long runId = run.getId();
        DeriveActivity.begin(runId, "⑧ AI 质检快检（五项）");
        try {
            return llmJson.ask(
                    new LlmPort.ChatRequest(NODE_QC, novel.getId(), null, runId,
                            List.of(LlmPort.Message.system(QC_PROMPT), LlmPort.Message.user(user)),
                            0.2),
                    root -> parseQc(root, issues), 2,
                    (think, piece) -> DeriveActivity.touch(runId, piece));
        } finally {
            DeriveActivity.end(runId);
        }
    }

    JsonNode parseQc(JsonNode root, List<String> issues) {
        for (String key : List.of("fulfillment", "pacing", "voice", "domain", "logic")) {
            JsonNode n = root.path(key);
            if (!n.has("pass")) {
                throw new LlmJson.Bad("缺 " + key + ".pass");
            }
            if (!n.path("pass").asBoolean(false)) {
                issues.add(key + "：" + n.path("note").asText("未通过"));
            }
        }
        root.path("issues").forEach(i -> issues.add(i.asText()));
        return root;
    }

    String buildQcUser(RunDO run, NovelDO novel, List<ChapterDO> chapters, List<BeatInstanceDO> beats) {
        return buildQcUser(run, novel, chapters, beats, List.of());
    }

    String buildQcUser(RunDO run, NovelDO novel, List<ChapterDO> chapters, List<BeatInstanceDO> beats,
                       List<WorldStateDO> ledger) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.put("作品名", novel.getTitle());
        root.put("主角", readProtagonist(novel));
        root.set("域", DeriveWriteService.readTreeSafe(run.getDomainSnapshot()));
        root.set("类别", DeriveWriteService.readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        if (run.getParamIntent() != null && !run.getParamIntent().isBlank()) {
            root.put("题材意图", run.getParamIntent());
        }
        String wv = DeriveWriteService.extractWorldviewPremise(run.getParamIntent());
        if (wv != null) {
            root.put("世界观前提", wv);
        }
        // ---- 文风卡（v3.6 方案B：voice ③ 文风贴合的对照基准；优先级与⑦同构）----
        if (run.getParamStyle() != null && !run.getParamStyle().isBlank()) {
            root.put("目标文风", run.getParamStyle());
        } else {
            JsonNode sp = DeriveWriteService.readTreeSafe(novel.getStorySettings()).path("style_profile");
            if (sp.isObject()) {
                root.set("文风卡", sp);
            }
        }
        JsonNode assets = DeriveWriteService.readTreeSafe(novel.getStoryAssets());
        root.set("人物人设卡", assets.path("cast"));
        if (assets.path("truth").isObject()) {
            root.set("真相卡", assets.path("truth"));
        }
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
        if (ledger != null && !ledger.isEmpty()) {
            var wArr = root.putArray("世界状态账（逐章快照；logic⑥ 对照用）");
            for (WorldStateDO w : ledger) {
                var n = wArr.addObject();
                n.put("chapter_no", w.getChapterNo());
                n.set("state", DeriveWriteService.readTreeSafe(w.getState()));
            }
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

    List<String> genreQcIssues(RunDO run, NovelDO novel, List<ChapterDO> chapters) {
        if (obligationMapper == null) {
            return List.of();
        }
        List<StoryObligation> obligations = new ArrayList<>();
        for (DeriveStoryObligationDO row : obligationMapper.selectByRun(run.getId())) {
            obligations.add(new StoryObligation(row.getObligationKey(),
                    row.getChapterNo() == null ? 0 : row.getChapterNo(), row.getKind(),
                    row.getTargetKey(), Boolean.TRUE.equals(row.getRequired()),
                    Boolean.TRUE.equals(row.getForbidden()), row.getSeverity(),
                    row.getSourceContract(), readJsonObject(row.getPayload())));
        }
        if (obligations.isEmpty()) {
            return List.of();
        }
        StoryKernel kernel = new StoryKernelAssembler().assemble(run, null, List.of(), List.of(),
                List.of(), List.of(), obligations, evidenceIndex(run.getId()));
        List<GenreQcAuditor.ChapterText> texts = chapters.stream()
                .map(chapter -> new GenreQcAuditor.ChapterText(
                        chapter.getChapterNo() == null ? 0 : chapter.getChapterNo(),
                        chapter.getContent() == null ? "" : chapter.getContent()))
                .toList();
        GenreQcAuditor.CompiledDraft draft =
                new GenreQcAuditor.CompiledDraft(texts, worldStates(run, novel, chapters));
        GenreProfile profile = profile(run);
        List<com.zzdzz.novelgen.derive.plan.PlanContracts.PlanIssue> issues =
                "MYSTERY".equals(profile.genre()) && mysteryQcAuditor != null
                        ? mysteryQcAuditor.audit(draft, kernel, profile)
                        : auditGenreQc(run, profile, kernel, evidenceIndex(run.getId()), draft);
        return issues.stream().map(issue -> issue.code() + "：" + issue.message()).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<com.zzdzz.novelgen.derive.plan.PlanContracts.PlanIssue> auditGenreQc(
            RunDO run, GenreProfile profile, StoryKernel kernel, StoryEvidenceIndex evidenceIndex,
            GenreQcAuditor.CompiledDraft draft) {
        GenreContract contract = contractService == null ? null : contractService.loadGenreContract(run);
        if (contract != null) {
            return profile.auditQc(contract, kernel, evidenceIndex, draft);
        }
        return genreQcAuditor == null ? List.of() : genreQcAuditor.audit(draft, kernel, profile);
    }

    private StoryEvidenceIndex evidenceIndex(long runId) {
        StoryEvidenceIndex.Builder builder = StoryEvidenceIndex.builder();
        if (evidenceMapper == null) {
            return builder.build();
        }
        for (DeriveStoryObligationEvidenceDO row : evidenceMapper.selectByRun(runId)) {
            builder.addObligationEvidence(new StoryObligationEvidence(
                    row.getObligationKey(),
                    row.getChapterNo() == null ? 0 : row.getChapterNo(),
                    row.getEvidenceKind(),
                    row.getEventKey() == null ? "DB:" + row.getId() : row.getEventKey(),
                    row.getBeatSeq(),
                    readJsonStringList(row.getEntityKeys()),
                    readJsonStringList(row.getThreadKeys()),
                    row.getFact(),
                    row.getSource(),
                    row.getConfidence() == null ? 1.0 : row.getConfidence()));
        }
        return builder.build();
    }

    private Map<Integer, Map<String, String>> worldStates(RunDO run, NovelDO novel,
                                                           List<ChapterDO> chapters) {
        Map<Integer, Map<String, String>> out = new java.util.LinkedHashMap<>();
        if (worldStateMapper == null || novel == null || novel.getId() == null) {
            return out;
        }
        for (ChapterDO chapter : chapters) {
            WorldStateDO row = worldStateMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorldStateDO>()
                            .eq(WorldStateDO::getNovelId, novel.getId())
                            .lt(WorldStateDO::getChapterNo, chapter.getChapterNo())
                            .orderByDesc(WorldStateDO::getChapterNo)
                            .last("LIMIT 1"));
            Map<String, String> state = new java.util.LinkedHashMap<>();
            if (row != null) {
                readJsonObject(row.getState()).forEach((key, value) ->
                        state.put(key, String.valueOf(value)));
            }
            out.put(chapter.getChapterNo(), state);
        }
        return out;
    }

    private Map<String, Object> readJsonObject(String json) {
        try {
            JsonNode node = STATIC_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) {
                return Map.of();
            }
            return STATIC_MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> readJsonStringList(String json) {
        try {
            JsonNode node = STATIC_MAPPER.readTree(json == null || json.isBlank() ? "[]" : json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText("").isBlank()) {
                    out.add(item.asText(""));
                }
            });
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private GenreProfile profile(RunDO run) {
        GenreProfileRegistry registry = genreProfileRegistry;
        if (registry == null) {
            registry = new GenreProfileRegistry(List.of(new MysteryProfile(), new RomanceProfile()));
        }
        return registry.require(run.getGenre() == null || run.getGenre().isBlank()
                ? "MYSTERY" : run.getGenre());
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
