package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.plan.PlanJsonViews;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.vo.derive.GateCriterionVO;
import com.zzdzz.novelgen.model.vo.derive.GateVO;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P6 立项闸（⑥b 全窗完成后、⑦ 开写前的 LLM 立项评审，六判据）。
 *
 * <p>模型只输出六项结构化审稿，不再拥有终态决定权。Java 按严重度统一裁决：
 * 只有 ADVISORY 时放行；存在 HARD/FIXABLE 则前两轮回炉，第三轮才熔断。
 * 评审记录落 run.step_status.steps.GATE（L2 层，无 DDL）；⑦ writeNext 要求 GATE=PASS 才开写。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveGateService {

    public static final String NODE_GATE = "derive_gate";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();
    static final List<String> CRITERION_KEYS = List.of(
            "single_rule", "budget", "motive_chain", "closure", "function_lock", "motif_dedup");
    private static final Set<String> SEVERITIES = Set.of("HARD", "FIXABLE", "ADVISORY");
    private static final Pattern PLAN_VOLUME_TARGET =
            Pattern.compile("PLAN_VOLUME:[1-9]\\d*");

    static final String GATE_PROMPT = """
            你是小说大纲立项评审员。输入含：类别/产品形态/字数带/结局形状/主线、人物表（cast 含 persona）、
            真相卡（若有）、brief、全书章纲（每章 no/title/outline）、拍清单（seq/拍型/归属章/状态/filled 情节点）、
            母题黑名单（跨稿复读母题，命中要换写）。
            只依据给出材料评审。你的职责是“诊断”，不是决定放行或熔断；最终动作由系统按结构化结果计算。
            六判据逐项输出 pass、severity、note、issues、repairTarget：
            1. 规则单轨：真相卡存在时，诡计/手法层只允许一套可理性化规则——超自然只能做氛围，
               不得进入解释通道；两套规则并存判 fail；
            2. 体量预算：主线 ≤1 条；支线（独立人物线/赌局/复仇/私奔类）≤1（中篇）或 ≤2（长篇）；
               正文死亡/命案 ≤4 起；机关+死亡+揭穿类高密度事件中篇 ≤3、长篇 ≤5；
               拍总量与字数带折算明显超载（事件拍占绝对多数且支线塞不进章容量）判 fail；
            3. 动机链：主要人物每次立场/阵营变化在章纲里能找到 ≥1 个铺垫章；
               连续翻转无中间台阶、反派动机一句带过判 fail；
            4. 闭合：真相卡存在时，结局章（reveal_chapter 及后 1~2 章）章纲必须写明
               culprit/motive/method 三要素的兑现动作；先写死案情、主题余韵殿后合法，
               案情悬空/替身未决/真凶未点名判 fail；
            5. 职能锁定：主视角/侦探的行为与 persona 方法论一致（如"只记不断"者不得
               亲自潜入/肉搏/夺人质）；侦探与被卷入者职能重叠到读者会认错主视角判 fail；
            6. 母题查重：章纲与母题黑名单逐条比对，命中 ≥3 处（或核心转折母题命中 ≥2 处）
               则 fail——要求 directive 给出替换方向（换动机载体/换收尾动作/换嫁祸手段）。

            severity 口径：
            - pass=true 时 severity 固定 ADVISORY、repairTarget 固定 NONE；note 写通过依据；
            - fail 且是局部可改问题时 severity=FIXABLE；
            - fail 且存在根本矛盾、规则无解、人物状态无法闭合时 severity=HARD；
            - 只有真正会让当前计划写出硬伤时才判 fail；仅仅“还能更好、可以更精彩”要 pass，
              在 note 里提醒，不要把审美偏好升级成阻断项。

            repairTarget 只许四类：
            - SCALE：卷数/章数/字数规模本身不成立；
            - SPINE：主谜、最终真相、真相阶梯、角色功能弧光或全局线程合同有根本错误；
            - PLAN_VOLUME:n：第 n 卷局部问题、章功能、拍路由、角色高光或卷闭合有错；
            - DRAFT：仅章纲文字与 filled 情节点的局部表达需要重写。

            issues 必须引用可核对事实（章号、角色名、拍号或规则），禁止“逻辑不够好”这类空话。
            输出 JSON 对象：
            {"criteria":{
              "single_rule":{"pass":true,"severity":"ADVISORY","note":"只采用一套可解释规则",
                "issues":[],"repairTarget":"NONE"},
              "budget":{"pass":false,"severity":"FIXABLE","note":"第2卷事件过密",
                "issues":["第18-21章连续4章命案，超出中篇预算"],"repairTarget":"PLAN_VOLUME:2"},
              "motive_chain":{"pass":true,"severity":"ADVISORY","note":"阵营变化有铺垫",
                "issues":[],"repairTarget":"NONE"},
              "closure":{"pass":true,"severity":"ADVISORY","note":"真相三要素有兑现",
                "issues":[],"repairTarget":"NONE"},
              "function_lock":{"pass":true,"severity":"ADVISORY","note":"职能未串线",
                "issues":[],"repairTarget":"NONE"},
              "motif_dedup":{"pass":true,"severity":"ADVISORY","note":"未命中黑名单",
                "issues":[],"repairTarget":"NONE"}
             },
             "issues":["汇总需要处理的硬问题，无则空数组"],
             "directive":"给出可执行减法指令（砍什么/留什么/改什么），无则空串"}
            只输出 JSON 对象，无其他文字。
            """;

    record GateReview(
            List<GateCriterionVO> criteria,
            List<String> issues,
            String directive) {
    }

    record GateDecision(
            String verdict,
            String action,
            String repairTarget,
            List<String> issues,
            List<GateCriterionVO> criteria) {
    }

    private final RunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final WordlistMapper wordlistMapper;
    private final LlmJson llmJson;
    @Autowired(required = false)
    private DerivePlanValidationService planValidationService;
    @Autowired(required = false)
    private VolumePlanMapper volumePlanMapper;
    @Autowired(required = false)
    private CharacterPlanMapper characterPlanMapper;
    @Autowired(required = false)
    private StoryThreadMapper storyThreadMapper;

    /** ⑥b→⑦ 之间跑一次立项评审。 */
    public GateVO gate(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!"DRAFTING".equals(run.getStatus()) || run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "run 应处于 DRAFTING（⑥b 已完成），当前 " + run.getStatus());
        }
        long novelId = run.getNovelId();
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        long noOutline = chapters.stream()
                .filter(c -> c.getDraftOutline() == null || c.getDraftOutline().isBlank()).count();
        if (chapters.isEmpty() || noOutline > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥b 未完成：无纲章 " + noOutline + "/" + chapters.size() + "，先循环 POST /draft 至 allDone");
        }
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq));
        NovelDO novel = novelMapper.selectById(novelId);

        int prevRound = gateRound(run.getStepStatus());
        int round = prevRound + 1;

        List<String> motifs = wordlistMapper.selectList(new LambdaQueryWrapper<WordlistDO>()
                        .eq(WordlistDO::getKind, "BLACKLIST")
                        .eq(WordlistDO::getGrp, "MOTIF"))
                .stream().map(WordlistDO::getWord).filter(w -> w != null && !w.isBlank()).toList();
        String user = buildUser(run, novel, chapters, beats, motifs);

        GateReview review;
        DeriveActivity.begin(runId, "P6 立项闸评审（六判据）");
        try {
            review = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_GATE, novelId, null, runId,
                            List.of(LlmPort.Message.system(GATE_PROMPT), LlmPort.Message.user(user)), 0.2),
                    DeriveGateService::parseGate, 3, (think, piece) -> DeriveActivity.touch(runId, piece));
        } finally {
            DeriveActivity.end(runId);
        }

        PlanValidationVO planCheck = planValidationService == null
                ? null : planValidationService.validateBook(runId);
        GateDecision decision = decideGate(review, planCheck, prevRound);

        run.setStepStatus(withGate(
                run.getStepStatus(), decision.action(), round, decision.issues(),
                review.directive(), decision.repairTarget(), decision.criteria()));
        runMapper.updateStepStatus(runId, run.getStepStatus());
        if ("REWORK".equals(decision.action())) {
            runMapper.updateStatus(runId, "REWORK", null);
        } else if ("FAILED".equals(decision.action())) {
            runMapper.updateStatus(runId, "FAILED", null);
        }
        log.info("P6立项闸：runId={} round={} verdict={} action={} issues={} directive={}",
                runId, round, decision.verdict(), decision.action(), decision.issues().size(),
                review.directive().length() > 80
                        ? review.directive().substring(0, 80) + "…" : review.directive());
        return new GateVO(runId, novelId, decision.verdict(), decision.action(), round,
                decision.issues(), review.directive(), decision.repairTarget(),
                decision.criteria());
    }

    // ---- 输入构造 ----

    String buildUser(RunDO run, NovelDO novel, List<ChapterDO> chapters,
                     List<BeatInstanceDO> beats, List<String> motifs) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        root.set("类别", DeriveDraftService.readTreeSafe(run.getParamTags()));
        root.put("产品形态", run.getParamForm());
        root.put("字数带", run.getParamWordsMin() + "~" + run.getParamWordsMax());
        root.put("结局形状", run.getParamOutcome());
        root.put("主线", run.getWelded() == null ? "" : run.getWelded());
        JsonNode assets = DeriveDraftService.readTreeSafe(novel.getStoryAssets());
        root.set("人物表", assets.path("cast"));
        root.put("protagonist", assets.path("protagonist").asText(""));
        if (assets.path("truth").isObject()) {
            root.set("真相卡", assets.path("truth"));
        }
        root.set("brief", assets.path("brief"));
        if (volumePlanMapper != null) {
            root.set("卷合同", PlanJsonViews.volumes(volumePlanMapper.selectList(
                    new LambdaQueryWrapper<VolumePlanDO>()
                            .eq(VolumePlanDO::getRunId, run.getId())
                            .orderByAsc(VolumePlanDO::getVolumeNo))));
        }
        if (characterPlanMapper != null) {
            root.set("角色叙事计划", PlanJsonViews.characters(characterPlanMapper.selectList(
                    new LambdaQueryWrapper<CharacterPlanDO>()
                            .eq(CharacterPlanDO::getRunId, run.getId())
                            .orderByAsc(CharacterPlanDO::getCharacterKey))));
        }
        if (storyThreadMapper != null) {
            root.set("线程计划", PlanJsonViews.threads(storyThreadMapper.selectList(
                    new LambdaQueryWrapper<StoryThreadDO>()
                            .eq(StoryThreadDO::getRunId, run.getId())
                            .orderByAsc(StoryThreadDO::getThreadKey))));
        }
        var chArr = root.putArray("章纲");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("outline", c.getDraftOutline() == null ? "" : c.getDraftOutline());
        }
        var bArr = root.putArray("拍清单");
        for (BeatInstanceDO b : beats) {
            var n = bArr.addObject();
            n.put("seq", b.getSeq());
            n.put("beat_type", b.getBeatType());
            n.put("chapter", b.getChapterNo());
            n.put("status", b.getStatus());
            n.put("filled", b.getFilledText() == null ? "" : b.getFilledText());
        }
        ArrayNode mArr = root.putArray("母题黑名单");
        motifs.forEach(mArr::add);
        return root.toString();
    }

    // ---- 解析与判定（纯函数，单测覆盖） ----

    static GateReview parseGate(JsonNode root) {
        if (!root.path("issues").isArray()) {
            throw new LlmJson.Bad("issues 必须是数组（可为空）");
        }
        if (!root.has("directive")) {
            throw new LlmJson.Bad("缺 directive（可为空串，但键必须在）");
        }
        JsonNode criteriaNode = root.path("criteria");
        if (!criteriaNode.isObject()) {
            throw new LlmJson.Bad("criteria 必须是对象");
        }
        List<GateCriterionVO> criteria = new ArrayList<>();
        for (String key : CRITERION_KEYS) {
            JsonNode node = criteriaNode.path(key);
            if (!node.isObject()) {
                throw new LlmJson.Bad("criteria." + key + " 必须是对象");
            }
            if (!node.path("pass").isBoolean()) {
                throw new LlmJson.Bad("criteria." + key + ".pass 必须是布尔值");
            }
            boolean pass = node.path("pass").asBoolean();
            String severity = node.path("severity").asText("").trim();
            if (!SEVERITIES.contains(severity)) {
                throw new LlmJson.Bad("criteria." + key
                        + ".severity 必须是 HARD|FIXABLE|ADVISORY");
            }
            String note = node.path("note").asText("").trim();
            if (note.isEmpty()) {
                throw new LlmJson.Bad("criteria." + key + ".note 不能为空");
            }
            if (!node.path("issues").isArray()) {
                throw new LlmJson.Bad("criteria." + key + ".issues 必须是数组");
            }
            List<String> issues = new ArrayList<>();
            node.path("issues").forEach(n -> {
                String issue = n.asText("").trim();
                if (!issue.isEmpty()) {
                    issues.add(issue);
                }
            });
            String target = node.path("repairTarget").asText("").trim();
            if (!isValidTarget(target)) {
                throw new LlmJson.Bad("criteria." + key
                        + ".repairTarget 必须是 NONE|DRAFT|SPINE|SCALE|PLAN_VOLUME:n");
            }
            if (!pass && !"ADVISORY".equals(severity) && "NONE".equals(target)) {
                throw new LlmJson.Bad("criteria." + key
                        + " 未通过时必须给非 NONE 回炉目标");
            }
            if (pass && !"ADVISORY".equals(severity)) {
                throw new LlmJson.Bad("criteria." + key
                        + " 通过时 severity 必须为 ADVISORY");
            }
            criteria.add(new GateCriterionVO(key, pass, severity, note, issues, target));
        }
        List<String> issues = new ArrayList<>();
        root.path("issues").forEach(n -> {
            String issue = n.asText("").trim();
            if (!issue.isEmpty()) {
                issues.add(issue);
            }
        });
        return new GateReview(criteria, issues, root.path("directive").asText(""));
    }

    /** Java 统一裁决：模型只报问题，放行/回炉/熔断与目标层级都在这里决定。 */
    static GateDecision decideGate(GateReview review, PlanValidationVO planCheck, int prevRound) {
        LinkedHashSet<String> issues = new LinkedHashSet<>(review.issues());
        List<String> targets = new ArrayList<>();
        for (GateCriterionVO criterion : review.criteria()) {
            criterion.issues().forEach(issue -> issues.add(
                    criterionLabel(criterion.key()) + "：" + issue));
            if (!criterion.pass() && !"ADVISORY".equals(criterion.severity())) {
                targets.add(criterion.repairTarget());
            }
        }
        if (planCheck != null && !planCheck.passed()) {
            for (PlanValidationVO.Issue issue : planCheck.hardErrors()) {
                issues.add(issue.code() + "：" + issue.message());
                targets.add(issue.repairTarget());
            }
        }
        if (targets.isEmpty()) {
            return new GateDecision("GO", "PASS", "NONE",
                    new ArrayList<>(issues), review.criteria());
        }
        String repairTarget = mostUpstreamTarget(targets);
        boolean exhausted = prevRound >= 2;
        return new GateDecision(exhausted ? "REJECT" : "REWORK",
                exhausted ? "FAILED" : "REWORK", repairTarget,
                new ArrayList<>(issues), review.criteria());
    }

    // ---- step_status.steps.GATE 读写（合并既有键，L2 层） ----

    static int gateRound(String stepStatus) {
        return readGate(stepStatus).path("round").asInt(0);
    }

    static boolean gatePassed(String stepStatus) {
        return "PASS".equals(readGate(stepStatus).path("status").asText(""));
    }

    static String withGate(String stepStatus, String status, int round,
                           List<String> issues, String directive) {
        return withGate(stepStatus, status, round, issues, directive, "NONE");
    }

    static String withGate(String stepStatus, String status, int round,
                           List<String> issues, String directive, String repairTarget) {
        return withGate(stepStatus, status, round, issues, directive, repairTarget, List.of());
    }

    static String withGate(String stepStatus, String status, int round,
                           List<String> issues, String directive, String repairTarget,
                           List<GateCriterionVO> criteria) {
        ObjectNode criteriaNode = STATIC_MAPPER.createObjectNode();
        for (GateCriterionVO criterion : criteria == null ? List.<GateCriterionVO>of() : criteria) {
            ObjectNode node = criteriaNode.putObject(criterion.key());
            node.put("pass", criterion.pass());
            node.put("severity", criterion.severity());
            node.put("note", criterion.note());
            ArrayNode issueArr = node.putArray("issues");
            criterion.issues().forEach(issueArr::add);
            node.put("repairTarget", criterion.repairTarget());
        }
        return withGateNode(stepStatus, status, round, issues, directive,
                repairTarget, criteriaNode);
    }

    private static String withGateNode(String stepStatus, String status, int round,
                                       List<String> issues, String directive,
                                       String repairTarget, JsonNode criteria) {
        ObjectNode root = stepsRoot(stepStatus);
        ObjectNode gate = root.with("steps").putObject("GATE");
        gate.put("status", status);
        gate.put("round", round);
        gate.put("directive", directive == null ? "" : directive);
        gate.put("repairTarget", repairTarget == null ? "NONE" : repairTarget);
        ArrayNode arr = gate.putArray("issues");
        issues.forEach(arr::add);
        if (criteria != null && criteria.isObject()) {
            gate.set("criteria", criteria.deepCopy());
        } else {
            gate.putObject("criteria");
        }
        return root.toString();
    }

    /** ⑥b 回炉清稿时重置 GATE 状态——**保留 directive/issues**（重做必须按减法指令执行，2026-10-18 修）。 */
    static String gateReset(String stepStatus) {
        ObjectNode root = stepsRoot(stepStatus);
        ObjectNode gate = root.with("steps").withObject("GATE");
        int round = gate.path("round").asInt(0);
        String directive = gate.path("directive").asText("");
        String repairTarget = gate.path("repairTarget").asText("NONE");
        List<String> issues = new ArrayList<>();
        gate.path("issues").forEach(n -> issues.add(n.asText("")));
        return withGateNode(root.toString(), "PENDING", round, issues, directive,
                repairTarget, gate.path("criteria"));
    }

    /** 回炉重做时要喂回模型的减法指令（无则空串）。 */
    static String gateDirective(String stepStatus) {
        return readGate(stepStatus).path("directive").asText("");
    }

    static String repairTarget(String stepStatus) {
        return readGate(stepStatus).path("repairTarget").asText("NONE");
    }

    static String repairTarget(PlanValidationVO validation) {
        if (validation == null || validation.passed()
                || validation.hardErrors().isEmpty()) {
            return "DRAFT";
        }
        return validation.hardErrors().get(0).repairTarget();
    }

    private static JsonNode readGate(String stepStatus) {
        return stepsRoot(stepStatus).path("steps").path("GATE");
    }

    private static boolean isValidTarget(String target) {
        return "NONE".equals(target) || "DRAFT".equals(target) || "SPINE".equals(target)
                || "SCALE".equals(target) || PLAN_VOLUME_TARGET.matcher(target).matches();
    }

    private static String mostUpstreamTarget(List<String> targets) {
        String best = "DRAFT";
        int bestRank = -1;
        for (String target : targets) {
            String normalized = isValidTarget(target) ? target : "DRAFT";
            int rank = switch (normalized) {
                case "SCALE" -> 4;
                case "SPINE" -> 3;
                case "DRAFT" -> 1;
                case "NONE" -> 0;
                default -> 2;
            };
            if (rank > bestRank) {
                best = normalized;
                bestRank = rank;
            }
        }
        return best;
    }

    private static String criterionLabel(String key) {
        return switch (key) {
            case "single_rule" -> "规则单轨";
            case "budget" -> "体量预算";
            case "motive_chain" -> "动机链";
            case "closure" -> "闭合";
            case "function_lock" -> "职能锁定";
            case "motif_dedup" -> "母题查重";
            default -> key;
        };
    }

    private static ObjectNode stepsRoot(String stepStatus) {
        JsonNode root;
        try {
            root = STATIC_MAPPER.readTree(stepStatus == null || stepStatus.isBlank() ? "{}" : stepStatus);
        } catch (Exception e) {
            root = STATIC_MAPPER.createObjectNode();
        }
        if (!root.isObject()) {
            root = STATIC_MAPPER.createObjectNode();
        }
        ObjectNode obj = (ObjectNode) root;
        if (!obj.path("steps").isObject()) {
            obj.putObject("steps");
        }
        if (!obj.has("v")) {
            obj.put("v", 1);
        }
        return obj;
    }
}
