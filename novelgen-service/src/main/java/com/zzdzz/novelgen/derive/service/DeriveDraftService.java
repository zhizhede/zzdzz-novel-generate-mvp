package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.DraftHistoryMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationEvidenceMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
import com.zzdzz.novelgen.derive.plan.PlanJsonViews;
import com.zzdzz.novelgen.derive.story.DraftWindowObligationValidator;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryObligationEvidence;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.DraftHistoryDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationEvidenceDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.WorkFingerprintDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.DraftVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑥b 生成具体稿（v3.7 分批：全局头 ⑥b-0 + 连续章段窗 ⑥b-1..n，幂等单元制）。
 *
 * <p>每次调用只推进一个单元：头未完成做头；否则取第一个未完成窗；全部完成后终局对账
 * 并 run→DRAFTING。头产出全书级实体（书名/主角/cast/truth/brief，落 story_assets），
 * 窗逐批回填 beat_instance.filled_text 与 chapters.draft_outline（每窗落库、进度可见）。
 * REWORK 入口：归档正文 + 清稿 + 清头产物后回 PLANNING（本调用返回 RESET，客户端续调）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveDraftService {

    public static final String NODE_DRAFT = "derive_draft";

    /** 窗宽 4 章（2026-10-18 提速：今晚模型单窗 8 章输出失控屡超 10 分钟；4 章减半单次输出）。 */
    static final int WINDOW_CHAPTERS = 4;
    static final int WINDOW_BEATS = 6;

    /** 称谓式人名（X先生/X老师/X警官…）：模型新造人名的高发形态。 */
    static final Pattern PERSON_HONORIFIC = Pattern.compile(
            "([\u4e00-\u9fa5]{1,3})(先生|小姐|女士|老师|师傅|警官|医生|大夫|掌柜|老板|船长|店长|队长|主管|经理|阿姨|大叔|大爷|婆婆|爷爷|奶奶|同志)");

    /** 章纲里禁止出现的素材编号（GM- 线索 / REL- 关系 / OBJ- 器物）。 */
    static final Pattern ASSET_KEY = Pattern.compile("GM-[A-Z0-9-]+|REL-[A-Z0-9-]+|OBJ-[A-Z0-9-]+");

    /** 整词噪声：候选恰好是这些词就放行（那位/我先生…不是人名）。 */
    static final Set<String> NAME_NOISE = Set.of(
            "那位", "这位", "某位", "每个", "各位", "我", "你", "他", "她", "它", "咱",
            "大家", "有人", "本人", "对方", "死者", "生者", "前者", "后者");

    /** 字符级噪声：候选含这些字一律放行（指示词/量词/人称前缀）。 */
    static final String NOISE_CHARS = "那这某每该本一位个些种的我你他她它们";

    static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    /**
     * 全局头三段式（2026-10-18 夜）：原先一次要模型吐 title+summary+cast(每人五键 persona)+brief+truth，
     * 输出 3000–5200 字符，run22 六次调用六次畸形（如 {@code "flade":"怕什么":"怕发现…"} 键值边界错乱）。
     * 现拆成 A 定名与人物表 / B 人设卡 / C 摘要与真相卡，单次输出 ≤1000 字符；每段落库即幂等（headStage）。
     */
    static final String HEAD_A_PROMPT = """
            你在为一部小说写"全局头 A：定名与人物表"（不是正文，不写章纲）。输入含：域格、类别与节奏、
            结局形状、世界观、主线、题材意图、章节计划（只作规模参考）。
            要求：
            1. 起作品名、一句话概括、主角名，并给出 cast 全书人物表（主角排首，**固定恰好 3 人，不得增加路人/死者/画外角色**）；
            2. 人名 2~3 字；主角名不得出现在禁用名单里，不得用"沈X"式单姓+单字套路名；
            3. role=身份一句话（≤20字），gender=男或女；
            4. 此表是全书唯一合法人名来源，后续步骤不得再新增任何名字（含路人）；
            5. 若输入带『题材意图』：人名/称谓/器物/礼俗按该体系取名，禁止混入异文化元素。
            输出 JSON 对象：
            {"title":"作品名","summary":"一句话概括","protagonist":"主角名",
             "cast":[{"name":"…","role":"…","gender":"…"}]}
            只输出 JSON 对象，无其他文字。
            """;

    static final String HEAD_B_PROMPT = """
            你在为一部小说写"全局头 B：人物人设卡"（不是正文）。输入含：已定人名表（name/role/gender）、TruthContract（若有）、
            题材意图、域格、类别与节奏。
            要求：
            1. 给名单内每一个人各出一张五人设卡：禁止新增人名、禁止改名、禁止漏人；
            2. 五键必填且互不重复：temperament性格底色、conduct行为准则（什么绝不会做/什么一定做）、
            speech说话方式、desire核心欲望、flaw缺陷或恐惧；
            3. 每键 8~25 字，写具体可演的倾向，不写"善良勇敢"这类空话；
            4. 与『题材意图』及 TruthContract 已有动作一致，禁止矛盾：若真相合同要求主角进入现场，persona 必须允许受控进入或明确授权，不得写成绝对禁止；
            输出 JSON 对象：
            {"persona":[{"name":"名单内人名","temperament":"…","conduct":"…","speech":"…",
                         "desire":"…","flaw":"…"}]}
            只输出 JSON 对象，无其他文字。
            """;

    static final String HEAD_C_PROMPT = """
            你在为一部小说写"全局头 C：叙述摘要"（不是正文，不写章纲）。输入含：已定书名/
            主角/cast 人名表、域格、类别与节奏、结局形状、主线、章节计划（章号与预算）。
            要求：
            1. brief 拆分：{"premise":"人物设定/世界观/氛围/描写红线（全程可用，≤300字）",
            "ending":"结局与真相相关段落（无结局信息给空串）"}——premise 不得含结局答案，
            ending 集中放 twist/真凶/动机/结局走向；
            1b. 若输入带『目标文风』：premise 里的氛围与描写红线必须服从该文风——不许把腔调写成
                冷硬文书/卷宗/文言体，除非目标文风本身就是那个调子；premise 只约束"写什么可信"，
                不负责指定"用什么语体写"；
            2. 真相卡、真凶、动机、手法、线索章号一律由冻结合同决定，你不输出、也不得改写。
            只输出 JSON 对象：
            {"brief":{"premise":"…","ending":"…"}}
            只输出 JSON 对象，无其他文字。
            """;

    static final String BATCH_PROMPT = """
            你在为一部小说生成"具体稿·分批章纲"（不是正文）。输入含：全局头（protagonist/cast 人名表
            /brief/truth）、域格、类别与节奏、世界观、主线、前批章纲概览、已发生不可逆状态、
            本窗章节计划与本窗拍清单。
            要求：
            1. 每个本窗拍实例填实成具体情节点（1~2 句，写 filled）；filled 必须写清
               「谁 + 做了什么 + 结果/后果」，且至少出现一个 cast 名单内人名；
               禁止用「本窗不落位」「留待后续」「待定」「占位」等占位文本；
               确实用不上的拍才放进 skipped 数组，不能把拍写成占位句；skipped 只放没有出现在任何 beats 里的 seq，已经填实的 seq 不得再放进 skipped；
            2. 本窗每章给 draft_outline（本章具体大纲 2~3 句）与 title 定稿（≤12字，可沿用或改）；相邻章 outline 必须引入新增信息，禁止复述前章事件、证据或转折；系统已机械分配 seq 与章节，模型输出的重复/错挂 seq 会被系统归一，但仍应尽量按给定 seq 填写；
            3. 人名、性别、称谓严格沿用全局头 cast 表，filled 与 draft_outline 中出现的
               每一个有名人名都必须是 cast 表内的逐字原名，禁止新造任何新名字（含路人、死者、
               被提及者）；需要无名人物的动作时写“一名守卫/一个路人”等无名描述，或改用表内人名；
            4. 写纲无知之幕：章号 < reveal_chapter 的章（若输入带 truth），draft_outline 与 title
               只写在场角色认知内的情节走向，禁写 solution/真凶身份/解法/视角反转；
            5. 与前批章纲衔接：不推翻前批已定事实（人物关系、已发生事件、关键物件）；
            5b. 已发生不可逆状态是硬约束：其中记为死亡/离场/被囚/失去身份的人，本窗不得给他安排
               现场戏份（出场说话、行动、被当面拜访）；回忆、梦境、遗体、遗物、他人转述、信件引用
               不受此限，但必须在 draft_outline 里写明是哪种形式；
            5c. 每章必须给 time（本章时间跨度，如"当晚""三日后"）与 state_changes
               （本章造成、后续不可回退的变更；没有就给空数组）；
            6. 若输入带『题材意图』：走向与红线以它为准，拍清单只作结构骨架；
            7. 只输出本窗章纲结构，不写正文，不提素材来源与编号（对外只出现 seq）。
            8. 章级日程是硬约束：mustPlant 的每条线索必须在**本章** draft_outline 写出可观测现象（不写结论），
               并在 clueEvents 记 {"key":"…","act":"PLANT"}；mustPayoff 必须写出兑现动作并记 act=PAYOFF；
               其他章不得复述这些线索现象，禁止提前埋设/重复兑现。
            9. reveal=true 的**这一章**必须用 closingActs 覆盖 CULPRIT/MOTIVE/METHOD 三项；
               draft_outline 必须逐字写出 culpritName 与 motiveTag（两者都在输入里）；
               其后各章只写收束/余韵；reveal 之前不得出现真凶姓名+手法结论。
            10. 不得新增合同 clue 之外的证据/机关/线人；需要障碍时用无名环境描写（如"一块铁板"→改为"一处障碍"）。
            11. 输出 clueEvents/closingActs；没有就空数组，禁止省略。
            输出 JSON 对象：
            {"chapters":[{"no":1,"title":"…","draft_outline":"…","time":"当晚",
                          "state_changes":[{"who":"布兰","what":"死亡"},
                                           {"who":"薇拉","what":"得知","detail":"布兰与卧房案有关"}],
                          "clueEvents":[{"key":"GM-CLUE-01","act":"PLANT"}],
                          "closingActs":["CULPRIT","MOTIVE","METHOD"],
                          "beats":[{"seq":1,"filled":"…"}]}],
             "skipped":[…]}
            state_changes.what 只允许：死亡 / 离场 / 被囚 / 失去身份 / 关系改变 / 物件易主 / 得知
            （得知须用 detail 写知道了什么）。who 必须是 cast 名单内人名。
            只输出 JSON 对象，无其他文字。
            """;

    private final RunMapper runMapper;
    @Autowired(required = false)
    private DeriveContractService contractService;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final BeatInstanceMapper beatInstanceMapper;
    private final AssetWorldMapper worldMapper;
    private final AssetBeatMapper beatMapper;
    private final WorkFingerprintMapper fingerprintMapper;
    private final WorldStateMapper worldStateMapper;
    private final DraftHistoryMapper draftHistoryMapper;
    private final LlmJson llmJson;
    @Autowired(required = false)
    private VolumePlanMapper volumePlanMapper;
    @Autowired(required = false)
    private CharacterPlanMapper characterPlanMapper;
    @Autowired(required = false)
    private StoryThreadMapper storyThreadMapper;
    @Autowired(required = false)
    private DeriveFactService factService;
    @Autowired(required = false)
    private DeriveCharacterBusinessModelService businessModelService;
    /** 章级日程（合同投影）：⑥b 每章 mustPlant/mustPayoff/reveal 的唯一来源。 */
    @Autowired(required = false)
    private DeriveChapterScheduleService scheduleService;
    @Autowired(required = false)
    private DeriveStoryObligationMapper obligationMapper;
    @Autowired(required = false)
    private DraftWindowObligationValidator draftWindowObligationValidator;
    @Autowired(required = false)
    private DeriveStoryObligationEvidenceMapper evidenceMapper;

    /**
     * PLAN_VOLUME 局部回炉：只清目标卷的章纲（拍已由 replanVolume 重建），状态回 PLANNING，
     * GATE 轮次与减法指令保留。绝不清整本——局部修复不许放大成整本重生成。
     */
    public void resetVolumeDraft(long runId, int volumeNo) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 未挂成品书，无法局部回炉");
        }
        int cleared = chapterMapper.clearDraftOutlinesByVolume(run.getNovelId(), volumeNo);
        runMapper.updateStatus(runId, "PLANNING", null);
        runMapper.updateStepStatus(runId, DeriveGateService.gateReset(run.getStepStatus()));
        log.info("PLAN_VOLUME 局部回炉：runId={} volumeNo={} 清章纲={}（其余卷具体稿保留）",
                runId, volumeNo, cleared);
    }

    /** DRAFT 局部回炉的作用域：受影响章号（升序）与受影响窗数。 */
    record ResetScope(List<Integer> affectedChapters, int windows) {
    }

    /**
     * DRAFT 级回炉：只清问题涉及的章纲与已填拍，**不清 cast/brief/truth，也不清整本**。
     *
     * <p>回炉目标由 P6 的 GATE issues/directive 里抽章号并只展开到受影响窗；抽不到才回退全章。
     * GATE 轮次与减法指令保留，供同一轮熔断计数。
     */
    public ResetScope resetForRepair(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        return resetForRepair(run);
    }

    ResetScope resetForRepair(RunDO run) {
        long runId = run.getId();
        if (run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "run 未挂成品书，无法局部回炉");
        }
        String repairTarget = DeriveGateService.repairTarget(run.getStepStatus());
        if (repairTarget.startsWith("PLAN_VOLUME:")) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "PLAN_VOLUME 回炉必须走 replanVolume + resetVolumeDraft（" + repairTarget + "）");
        }
        long novelId = run.getNovelId();
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        List<Integer> chapterNos = chapters.stream().map(ChapterDO::getChapterNo).toList();
        int chapterCount = chapterNos.stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> affected = repairChapters(run.getStepStatus(), chapterCount);
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>().eq(BeatInstanceDO::getRunId, runId));
        List<List<Integer>> allWindows =
                windowsByBeats(chapterNos, beats, WINDOW_CHAPTERS, WINDOW_BEATS);
        if (affected.isEmpty()) {
            affected = chapterNos;
            log.warn("DRAFT 回炉抽不到问题章，回退全章：runId={} 抽到={}",
                    runId, repairedChaptersForLog(run.getStepStatus(), chapterCount));
        } else {
            Set<Integer> affectedSet = new HashSet<>(affected);
            affected = allWindows.stream()
                    .filter(window -> window.stream().anyMatch(affectedSet::contains))
                    .flatMap(List::stream).sorted().distinct().toList();
            if (affected.size() == chapterNos.size()) {
                log.warn("DRAFT 回炉覆盖全部窗，等价全书重写：runId={}", runId);
            }
        }
        chapterMapper.clearDraftOutlinesByChapters(novelId, affected);
        beatInstanceMapper.resetFilledByChapters(runId, affected);
        runMapper.updateStatus(runId, "PLANNING", null);
        runMapper.updateStepStatus(runId, dropDraftStateForChapters(run.getStepStatus(), affected));
        int windows = affectedWindows(chapterNos, beats, affected);
        int first = affected.isEmpty() ? 0 : affected.get(0);
        int last = affected.isEmpty() ? 0 : affected.get(affected.size() - 1);
        log.info("DRAFT 局部回炉：runId={} 清章纲 {} 章（窗 {}/{}，章节 {}-{}），未受影响章保留",
                runId, affected.size(), windows, allWindows.size(), first, last);
        return new ResetScope(affected, Math.max(1, windows));
    }

    /** 从 GATE issues/directive 抽受影响的章号（区间/单点/英文 ch 三种写法；越界丢弃）。 */
    static List<Integer> repairChapters(String stepStatus, int chapterCount) {
        if (chapterCount < 1) {
            return List.of();
        }
        Set<Integer> chapters = new java.util.TreeSet<>();
        java.util.List<String> texts = new ArrayList<>();
        JsonNode gate = readTreeSafe(stepStatus).path("steps").path("GATE");
        gate.path("issues").forEach(node -> texts.add(node.asText("")));
        texts.add(gate.path("directive").asText(""));
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher range = java.util.regex.Pattern
                    .compile("第\\s*(\\d+)\\s*[~\\-至]\\s*(\\d+)\\s*章").matcher(text);
            while (range.find()) {
                int from = Integer.parseInt(range.group(1));
                int to = Integer.parseInt(range.group(2));
                for (int no = Math.min(from, to); no <= Math.max(from, to); no++) {
                    addChapter(chapters, no, chapterCount);
                }
            }
            Matcher single = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*章").matcher(text);
            while (single.find()) {
                addChapter(chapters, Integer.parseInt(single.group(1)), chapterCount);
            }
            Matcher english = java.util.regex.Pattern
                    .compile("ch(?:apter)?\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            while (english.find()) {
                addChapter(chapters, Integer.parseInt(english.group(1)), chapterCount);
            }
        }
        return List.copyOf(chapters);
    }

    private static void addChapter(Set<Integer> chapters, int chapterNo, int chapterCount) {
        if (chapterNo >= 1 && chapterNo <= chapterCount) {
            chapters.add(chapterNo);
        }
    }

    private static List<Integer> repairedChaptersForLog(String stepStatus, int chapterCount) {
        return repairChapters(stepStatus, chapterCount);
    }

    /** 受影响的窗数：按窗宽切分后，任一受影响章落在窗内即计入。 */
    static int affectedWindows(List<Integer> chapterNos, List<BeatInstanceDO> beats,
                               List<Integer> affected) {
        if (chapterNos.isEmpty()) {
            return 0;
        }
        Set<Integer> target = new HashSet<>(affected);
        int count = 0;
        for (List<Integer> window : windowsByBeats(chapterNos, beats,
                WINDOW_CHAPTERS, WINDOW_BEATS)) {
            if (window.stream().anyMatch(target::contains)) {
                count++;
            }
        }
        return count;
    }

    /** 清掉受影响章在 step_status.DRAFT.changes 里的旧状态（这些章会被整窗重写）。 */
    static String dropDraftStateForChapters(String stepStatus, List<Integer> affected) {
        JsonNode parsed = readTreeSafe(stepStatus);
        ObjectNode root = parsed.isObject()
                ? (ObjectNode) parsed.deepCopy() : STATIC_MAPPER.createObjectNode();
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        ObjectNode draft = steps.path("DRAFT").isObject()
                ? (ObjectNode) steps.path("DRAFT") : steps.putObject("DRAFT");
        Set<Integer> target = new HashSet<>(affected);
        ArrayNode kept = STATIC_MAPPER.createArrayNode();
        draft.path("changes").forEach(change -> {
            if (!target.contains(change.path("no").asInt(-1))) {
                kept.add(change);
            }
        });
        draft.set("changes", kept);
        return root.toString();
    }

    /** ⑥b：幂等推进一个单元（RESET/HEAD/BATCH/DONE，v3.7；客户端循环调用至 allDone）。 */
    public DraftVO draft(long runId) {
        try {
            return draftInner(runId);
        } finally {
            DeriveActivity.end(runId);
        }
    }

    /** 长任务实时活动快照（⑥b/闸/⑦/⑧ 共用；无活跃活动 active=false）。 */
    public com.zzdzz.novelgen.model.vo.derive.DraftProgressVO progress(long runId) {
        return DeriveActivity.snapshot(runId);
    }

    void beginProgress(long runId, String phase) {
        DeriveActivity.begin(runId, phase);
    }

    void touchProgress(long runId, String piece) {
        DeriveActivity.touch(runId, piece);
    }

    DraftVO draftInner(long runId) {
        RunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!("PLANNING".equals(run.getStatus()) || "REWORK".equals(run.getStatus()))) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥b 具体稿需要 run 处于 PLANNING/REWORK（当前 " + run.getStatus()
                            + "）——先让 ⑥a 章节计划完成");
        }
        if (run.getNovelId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "该 run 还没有成品书骨架（novel_id 为空＝⑥a 章节计划尚未完成）——"
                            + "先点「⑥a 生成章节计划」，成功后再做⑥b具体稿");
        }
        long novelId = run.getNovelId();

        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(new LambdaQueryWrapper<BeatInstanceDO>()
                .eq(BeatInstanceDO::getRunId, runId)
                .orderByAsc(BeatInstanceDO::getSeq));
        List<ChapterDO> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterDO>()
                .eq(ChapterDO::getNovelId, novelId)
                .orderByAsc(ChapterDO::getChapterNo));
        List<Integer> chapterNos = chapters.stream().map(ChapterDO::getChapterNo).toList();
        if (chapterNos.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "⑥b 需要先完成 ⑥a 章节计划（这本书还没有任何章行，真相卡也没章号可对）"
                            + "——先把⑥a 跑到「章节计划完成」再点⑥b");
        }
        List<List<Integer>> wins = windowsByBeats(
                chapterNos, beats, WINDOW_CHAPTERS, WINDOW_BEATS);

        if ("REWORK".equals(run.getStatus())) {
            String repairTarget = DeriveGateService.repairTarget(run.getStepStatus());
            if (repairTarget.startsWith("PLAN_VOLUME:")) {
                // 单一路径：PLAN_VOLUME 由 auto-flow 的 replanVolume + resetVolumeDraft 处理，
                // 这里再清一次会把局部修复放大成整本重生成。
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "PLAN_VOLUME 回炉必须走 replanVolume + resetVolumeDraft 单一路径（"
                                + repairTarget + "），不要在 ⑥b 入口整体清稿");
            }
            ResetScope scope = resetForRepair(run);
            return new DraftVO(runId, novelId, "", "", 0, 0, 0, "RESET", 0, scope.windows(), false);
        }

        NovelDO novel = novelMapper.selectById(novelId);
        boolean reasoning = reasoningGenre(run.getParamTags());
        String gateDirective = DeriveGateService.gateDirective(run.getStepStatus());

        int stage = headStage(novel, reasoning);
        if (stage < 3) {
            return headStep(run, novel, stage, chapters, chapterNos, reasoning, gateDirective);
        }

        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        Map<Integer, String> labelBySeq = buildLabelBySeq(beats);
        List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule =
                scheduleService == null ? List.of() : scheduleService.load(run);
        StoryContract batchContract = contractService == null ? null : contractService.load(run);
        for (int i = 0; i < wins.size(); i++) {
            List<Integer> win = wins.get(i);
            if (windowDone(win, chapters)) {
                continue;
            }
            List<BeatInstanceDO> winBeats = windowBeats(beats, win);
            Set<Integer> winSeqs = new LinkedHashSet<>();
            for (BeatInstanceDO b : winBeats) {
                winSeqs.add(b.getSeq());
            }
            Set<String> castNames = new LinkedHashSet<>();
            for (JsonNode c : assets.path("cast")) {
                castNames.add(c.path("name").asText(""));
            }
            Map<Integer, Integer> seqChapter = new HashMap<>();
            winBeats.forEach(b -> seqChapter.put(b.getSeq(), b.getChapterNo()));
            BatchContext ctx = new BatchContext(
                    new ArrayList<>(winSeqs), win, castNames, seqChapter, schedule);
            JsonNode priorChanges = readDraftState(run.getStepStatus());
            String user = buildBatchUser(run, chapters, win, winBeats, labelBySeq, worldLabel(run),
                    assets, gateDirective, priorChanges, schedule, batchContract);
            beginProgress(runId, "窗 " + (i + 1) + "/" + wins.size()
                    + "（章 " + win.get(0) + "-" + win.get(win.size() - 1) + "）");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
                            List.of(LlmPort.Message.system(batchPrompt(run)), LlmPort.Message.user(user)), 0.4),
                    root -> {
                        JsonNode parsed = parseBatch(root, ctx);
                        if (batchContract != null) {
                            List<String> drift = validateBatch(parsed, batchContract);
                            if (!drift.isEmpty()) {
                                throw new LlmJson.Bad(
                                        "CONTRACT_NAME_DRIFT：" + String.join("；", drift));
                            }
                            List<String> scheduleDrift = validateScheduleEvents(
                                    parsed, schedule, batchContract);
                            if (!scheduleDrift.isEmpty()) {
                                throw new LlmJson.Bad(String.join("；", scheduleDrift));
                            }
                        }
                        validateObligationWindow(parsed, ctx, runId);
                        return parsed;
                    }, 3, (think, piece) -> touchProgress(runId, piece));
            Map<Integer, String> filled = new HashMap<>();
            for (JsonNode c : out.path("chapters")) {
                for (JsonNode b : c.path("beats")) {
                    filled.put(b.path("seq").asInt(), b.path("filled").asText());
                }
            }
            Set<Integer> skipped = new HashSet<>();
            for (JsonNode s : out.path("skipped")) {
                skipped.add(skippedSeq(s));
            }
            int filledCount = 0;
            int skippedCount = 0;
            for (BeatInstanceDO b : winBeats) {
                if (skipped.contains(b.getSeq())) {
                    b.setStatus("SKIPPED");
                    skippedCount++;
                } else {
                    b.setFilledText(filled.get(b.getSeq()));
                    b.setStatus("PLANNED");
                    filledCount++;
                }
                beatInstanceMapper.updateFill(
                        b.getId(), b.getFilledText(), b.getStatus());
            }
            for (JsonNode c : out.path("chapters")) {
                int no = c.path("no").asInt();
                ChapterDO ch = chapters.stream().filter(x -> x.getChapterNo() == no).findFirst().orElse(null);
                if (ch == null) {
                    continue;
                }
                ch.setTitle(c.path("title").asText(ch.getTitle()));
                ch.setDraftOutline(c.path("draft_outline").asText(""));
                chapterMapper.updateDraftOutline(
                        ch.getId(), ch.getTitle(), ch.getDraftOutline());
                // 日程证据（clueEvents/closingActs）并入章功能合同，供 P6 与审计直接查库对表。
                String planContract = mergePlanContractEvents(ch.getPlanContract(), c);
                ch.setPlanContract(planContract);
                chapterMapper.updatePlanContract(ch.getId(), planContract);
            }
            runMapper.updateStepStatus(runId, appendDraftState(run.getStepStatus(), out.path("chapters")));
            log.info("⑥b-1 分批窗：runId={} novelId={} 窗{}/{} 章{}-{} 填实{} 跳过{} 状态变更{}",
                    runId, novelId, i + 1, wins.size(), win.get(0), win.get(win.size() - 1),
                    filledCount, skippedCount, countChanges(out.path("chapters")));
            return new DraftVO(runId, novelId, "", assets.path("protagonist").asText(""),
                    win.size(), filledCount, skippedCount, "BATCH", i + 1, wins.size(), false);
        }

        DraftRepairResult repair = normalizeDraftContent(chapters, beats, schedule);
        for (ChapterDO chapter : repair.changedChapters()) {
            chapterMapper.updateDraftOutline(chapter.getId(), chapter.getTitle(),
                    chapter.getDraftOutline());
        }
        for (BeatInstanceDO beat : repair.changedBeats()) {
            beatInstanceMapper.updateRoute(beat);
        }
        if (factService != null) {
            for (RepairEntry entry : repair.repairs()) {
                factService.recordRepair(runId, "DRAFT", entry.repairType(),
                        entry.before(), entry.after(), entry.reason(), "DRAFT");
            }
        }
        reconcileMissingBeats(runId, beats);
        globalReconcile(beats, chapters);
        runMapper.updateStatus(runId, "DRAFTING", null);
        log.info("⑥b 全部窗完成：runId={} novelId={} 窗数={} → DRAFTING", runId, novelId, wins.size());
        return new DraftVO(runId, novelId, novel.getTitle(), assets.path("protagonist").asText(""),
                chapters.size(), 0, 0, "DONE", wins.size(), wins.size(), true);
    }

    /** 取本窗参与具体稿的拍：跳过拍没有章号，必须在窗口过滤前排除，避免 contains(null) 空指针。 */
    static List<BeatInstanceDO> windowBeats(List<BeatInstanceDO> beats, List<Integer> window) {
        return beats.stream()
                .filter(b -> b.getChapterNo() != null && window.contains(b.getChapterNo()))
                .toList();
    }
    /** 全局头人设卡五键（顺序即提示词顺序）。 */
    static final List<String> PERSONA_KEYS =
            List.of("temperament", "conduct", "speech", "desire", "flaw");

    /**
     * 全局头完成度：0=未定名 1=缺人设卡 2=缺摘要（推理体裁还缺真相卡）3=齐。
     * 每段独立落库，故按缺失段续跑，不会因某段失败而把已成的段落重做。
     */
    static int headStage(NovelDO novel, boolean reasoning) {
        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        if (assets.path("protagonist").asText("").trim().isEmpty()) {
            return 0;
        }
        JsonNode cast = assets.path("cast");
        if (!cast.isArray() || cast.isEmpty()) {
            return 1;
        }
        for (JsonNode c : cast) {
            JsonNode persona = c.path("persona");
            if (!persona.isObject()) {
                return 1;
            }
            for (String k : PERSONA_KEYS) {
                if (persona.path(k).asText("").trim().isEmpty()) {
                    return 1;
                }
            }
        }
        if (assets.path("brief").path("premise").asText("").trim().isEmpty()) {
            return 2;
        }
        if (reasoning && assets.path("truth").path("solution").path("culprit").asText("").trim().isEmpty()) {
            return 2;
        }
        return 3;
    }

    /** 推进一步全局头：A 定名与人物表 → B 人设卡 → C 摘要与真相卡（单次输出小，畸形率低）。 */
    private DraftVO headStep(RunDO run, NovelDO novel, int stage, List<ChapterDO> chapters,
                             List<Integer> chapterNos, boolean reasoning, String gateDirective) {
        long runId = run.getId();
        long novelId = novel.getId();
        String worldLabel = worldLabel(run);
        if (stage == 0) {
            List<String> banned = bannedNames(run);
            HeadContext ctx = new HeadContext(chapterNos, banned, reasoning);
            String user = buildHeadUser(run, chapters, worldLabel, banned, gateDirective);
            beginProgress(runId, "全局头A 定名与人物表");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
                            List.of(LlmPort.Message.system(HEAD_A_PROMPT), LlmPort.Message.user(user)), 0.4),
                    root -> parseHeadBasic(root, ctx), 3, (think, piece) -> touchProgress(runId, piece));
            novelMapper.updateMeta(novelId, out.path("title").asText(""), out.path("summary").asText(""));
            ObjectNode castOut = materializeCastFromContract(run, out);
            novelMapper.updateCast(novelId, castOut.path("protagonist").asText(""),
                    bindCharacterKeys(runId, castOut.path("cast")));
            log.info("⑥b-0a 定名：runId={} novelId={} 书名={} 主角={} cast={}（人名一律取自冻结合同）",
                    runId, novelId, out.path("title").asText(""),
                    castOut.path("protagonist").asText(""), castOut.path("cast").size());
            return new DraftVO(runId, novelId, out.path("title").asText(""),
                    castOut.path("protagonist").asText(""), 0, 0, 0, "HEAD", 1, 3, false);
        }
        JsonNode assets = readTreeSafe(novel.getStoryAssets());
        String protagonist = assets.path("protagonist").asText("");
        if (stage == 1) {
            String user = buildPersonaUser(run, assets, worldLabel, gateDirective);
            beginProgress(runId, "全局头B 人物人设卡");
            JsonNode out = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
                            List.of(LlmPort.Message.system(HEAD_B_PROMPT), LlmPort.Message.user(user)), 0.5),
                    root -> parsePersona(root, assets), 3, (think, piece) -> touchProgress(runId, piece));
            String merged = mergePersona(assets.path("cast"), out);
            novelMapper.updateCast(novelId, protagonist, merged);
            log.info("⑥b-0b 人设卡：runId={} novelId={} 人数={}", runId, novelId, out.size());
            return new DraftVO(runId, novelId, assets.path("title").asText(""), protagonist,
                    0, 0, 0, "HEAD", 2, 3, false);
        }
        HeadContext ctx = new HeadContext(chapterNos, List.of(), reasoning);
        String user = buildBriefUser(run, assets, chapters, worldLabel, gateDirective);
        beginProgress(runId, "全局头C 叙述摘要");
        // 真相卡只读冻结合同：HEAD C 不再让 LLM 写真相（run58 的 HEAD_C 漂移/降级由此消失）。
        StoryContract contract = contractService == null ? null : contractService.load(run);
        LlmPort.ChatRequest briefRequest = new LlmPort.ChatRequest(NODE_DRAFT, novelId, null, runId,
                List.of(LlmPort.Message.system(HEAD_C_PROMPT), LlmPort.Message.user(user)), 0.3);
        JsonNode out = llmJson.ask(briefRequest, root -> parseBriefTruth(root, ctx), 3,
                (think, piece) -> touchProgress(runId, piece));
        novelMapper.updateBrief(novelId, out.path("brief").toString());
        JsonNode truth = contract == null ? null : contractService.frozenTruthCard(run);
        if ((truth == null || !truth.isObject()) && out.path("truth").isObject()) {
            // 无合同旧 run 的兼容回退（新链路合同必在 CONTRACT 单元冻结）。
            truth = out.path("truth");
        }
        if (truth != null && truth.isObject()) {
            novelMapper.updateTruth(novelId, truth.toString());
        }
        log.info("⑥b-0c 叙述摘要：runId={} novelId={} 有真相卡={}",
                runId, novelId, truth != null && truth.isObject());
        return new DraftVO(runId, novelId, assets.path("title").asText(""), protagonist,
                0, 0, 0, "HEAD", 3, 3, false);
    }

    /** 单测钩子：直接推进全局头某一段（跳过 draftInner 的幂等判定）。 */
    DraftVO headStepForTest(RunDO run, NovelDO novel, int stage, List<ChapterDO> chapters,
                            List<Integer> chapterNos, boolean reasoning, String gateDirective) {
        return headStep(run, novel, stage, chapters, chapterNos, reasoning, gateDirective);
    }

    /**
     * 合同 cast → novel.cast：protagonist 固定为合同 C1，cast 只允许合同里那三个人。
     * LLM 在 HEAD A 只出 title/summary；它给出的任何人名一律丢弃并留痕。
     */
    ObjectNode materializeCastFromContract(RunDO run, JsonNode llmOut) {
        ObjectNode out = llmOut != null && llmOut.isObject()
                ? (ObjectNode) llmOut.deepCopy() : STATIC_MAPPER.createObjectNode();
        if (contractService == null) {
            log.warn("合同服务未装配，HEAD A 退回 LLM cast（违反合同前置）：runId={}", run.getId());
            return out;
        }
        StoryContract contract = contractService.require(run);
        ArrayNode cast = out.putArray("cast");
        for (StoryContract.CharacterSlot slot : contract.cast()) {
            ObjectNode member = cast.addObject();
            member.put("characterKey", slot.key());
            member.put("name", slot.name());
            member.put("role", slot.identity());
            member.put("function", slot.role());
            member.put("gender", slot.gender());
        }
        String protagonist = contract.cast().stream()
                .filter(slot -> "C1".equals(slot.key()))
                .map(StoryContract.CharacterSlot::name)
                .findFirst()
                .orElseGet(() -> out.path("protagonist").asText(""));
        out.put("protagonist", protagonist);
        JsonNode llmCast = llmOut == null ? null : llmOut.path("cast");
        if (llmCast != null && llmCast.isArray()) {
            for (JsonNode member : llmCast) {
                String name = member.path("name").asText("").trim();
                boolean known = contract.cast().stream()
                        .anyMatch(slot -> slot.name().equals(name));
                if (!name.isEmpty() && !known) {
                    log.warn("HEAD A 给出合同外人名，已按合同丢弃：runId={} name={}", run.getId(), name);
                }
            }
        }
        return out;
    }

    /**
     * 分批窗产出与合同比对：合同外人名（称谓式）、合同外线程/线索 key、揭示章前写出
     * 真凶+手法结论，都判 CONTRACT_NAME_DRIFT / CONTRACT_DRIFT。
     *
     * @return 漂移问题清单（空=一致）
     */
    static List<String> validateBatch(JsonNode out, StoryContract contract) {
        List<String> drift = new ArrayList<>();
        if (contract == null || out == null) {
            return drift;
        }
        StringBuilder prose = new StringBuilder();
        for (JsonNode chapter : out.path("chapters")) {
            prose.append(chapter.path("draft_outline").asText("")).append(" ");
            prose.append(chapter.path("title").asText("")).append(" ");
            for (JsonNode beat : chapter.path("beats")) {
                prose.append(beat.path("filled").asText("")).append(" ");
            }
            for (JsonNode change : chapter.path("state_changes")) {
                prose.append(change.path("who").asText("")).append(" ");
            }
        }
        String text = prose.toString();
        for (String name : unknownPersonNames(text, contract)) {
            drift.add("CONTRACT_NAME_DRIFT：出现合同 cast 外的人名称谓「" + name + "」");
        }
        Set<String> threadKeys = new LinkedHashSet<>();
        contract.threads().forEach(thread -> threadKeys.add(thread.threadKey()));
        Matcher keyMatcher = ASSET_KEY.matcher(text);
        while (keyMatcher.find()) {
            String key = keyMatcher.group();
            if (!threadKeys.contains(key)) {
                drift.add("CONTRACT_THREAD_DRIFT：章纲里出现合同外编号 " + key);
            }
        }
        if (contract.truth() != null) {
            String culprit = contract.truth().culpritName();
            String method = contract.truth().method();
            int reveal = contract.truth().revealChapter();
            if (!culprit.isEmpty() && !method.isEmpty()) {
                for (JsonNode chapter : out.path("chapters")) {
                    int no = chapter.path("no").asInt(-1);
                    String outline = chapter.path("draft_outline").asText("");
                    if (no > 0 && no < reveal
                            && outline.contains(culprit) && outline.contains(method)) {
                        drift.add("CONTRACT_DRIFT：第 " + no + " 章在揭示章 " + reveal
                                + " 前写出了真凶与手法结论");
                    }
                }
            }
        }
        return drift;
    }

    /**
     * 抓“称谓式人名”（X先生/X老师/X警官…）：这是模型新造人名最常见的形态。
     * 指示词/量词前缀（那位/一位/我先生…）与合同 cast 的任何交集都放行，避免误杀。
     */
    static List<String> unknownPersonNames(String text, StoryContract contract) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Set<String> castNames = new LinkedHashSet<>();
        contract.cast().forEach(slot -> castNames.add(slot.name()));
        Matcher matcher = PERSON_HONORIFIC.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate.isEmpty() || NAME_NOISE.contains(candidate)) {
                continue;
            }
            boolean noisy = false;
            for (char ch : candidate.toCharArray()) {
                if (NOISE_CHARS.indexOf(ch) >= 0) {
                    noisy = true;
                    break;
                }
            }
            if (noisy) {
                continue;
            }
            boolean related = castNames.stream().anyMatch(name ->
                    name.contains(candidate) || candidate.contains(name));
            if (related) {
                continue;
            }
            if (!out.contains(matcher.group())) {
                out.add(matcher.group());
            }
        }
        return out;
    }
    /** 人设卡回填进已有 cast（按 name 对齐，保持原顺序与字段）。 */
    static String mergePersona(JsonNode cast, JsonNode personas) {
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode p : personas) {
            byName.put(p.path("name").asText(""), p);
        }
        var merged = STATIC_MAPPER.createArrayNode();
        for (JsonNode c : cast) {
            ObjectNode copy = c.deepCopy();
            JsonNode p = byName.get(c.path("name").asText(""));
            ObjectNode persona = copy.putObject("persona");
            for (String k : PERSONA_KEYS) {
                persona.put(k, p.path(k).asText(""));
            }
            merged.add(copy);
        }
        return merged.toString();
    }

    /** HEAD A 定名后把稳定角色 key 绑定到 cast；优先使用 displayNameHint，再按 key 顺序补位。 */
    String bindCharacterKeys(long runId, JsonNode cast) {
        if (characterPlanMapper == null || !cast.isArray()) {
            return cast.toString();
        }
        List<CharacterPlanDO> plans = characterPlanMapper.selectList(
                new LambdaQueryWrapper<CharacterPlanDO>()
                        .eq(CharacterPlanDO::getRunId, runId)
                        .orderByAsc(CharacterPlanDO::getCharacterKey));
        if (plans.isEmpty()) {
            return cast.toString();
        }
        Set<String> used = new LinkedHashSet<>();
        var out = STATIC_MAPPER.createArrayNode();
        for (JsonNode member : cast) {
            ObjectNode copy = member.deepCopy();
            String name = member.path("name").asText("");
            String key = plans.stream()
                    .filter(p -> name.equals(p.getDisplayNameHint()))
                    .map(CharacterPlanDO::getCharacterKey)
                    .filter(k -> !used.contains(k))
                    .findFirst()
                    .orElse(null);
            if (key == null) {
                key = plans.stream().map(CharacterPlanDO::getCharacterKey)
                        .filter(k -> !used.contains(k)).findFirst().orElse(null);
            }
            if (key != null) {
                used.add(key);
                copy.put("characterKey", key);
            }
            out.add(copy);
        }
        return out.toString();
    }

    /**
     * 打回归档（V113 draft_history，附录 A-2）：clearForRewrite 之前把 content 非空的章
     * 逐行落档，version=归档时 qc_round。清稿后 write 失败不再丢稿（run11 教训）。
     * 空正文/首跑（qc_round=0）自然零行，幂等。
     */
    void archiveRejectedDrafts(RunDO run, long novelId, List<ChapterDO> chapters) {
        int round = run.getQcRound() == null ? 0 : run.getQcRound();
        int version = Math.max(1, round);
        int n = 0;
        for (ChapterDO ch : chapters) {
            if (ch.getContent() == null || ch.getContent().isBlank()) {
                continue;
            }
            DraftHistoryDO h = new DraftHistoryDO();
            h.setRunId(run.getId());
            h.setNovelId(novelId);
            h.setChapterId(ch.getId());
            h.setChapterNo(ch.getChapterNo());
            h.setVersion(version);
            h.setQcRound(round);
            h.setContent(ch.getContent());
            h.setCharCount(ch.getCharCount() == null ? ch.getContent().length() : ch.getCharCount());
            h.setReason("REWORK");
            draftHistoryMapper.insert(h);
            n++;
        }
        if (n > 0) {
            log.info("打回归档：runId={} novelId={} 归档 {} 章（version={}）→ draft_history", run.getId(), novelId, n, version);
        }
    }

    // ---- 输入构造 ----

    String buildHeadUser(RunDO run, List<ChapterDO> chapters, String worldLabel,
                         List<String> banned, String gateDirective) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        root.put("主角名禁用名单", String.join("、", banned));
        var chArr = root.putArray("章节计划");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("budget", c.getBudget());
        }
        return root.toString();
    }

    String buildBatchUser(RunDO run, List<ChapterDO> chapters, List<Integer> window,
                          List<BeatInstanceDO> winBeats, Map<Integer, String> labelBySeq,
                          String worldLabel, JsonNode assets, String gateDirective,
                          JsonNode priorChanges) {
        return buildBatchUser(run, chapters, window, winBeats, labelBySeq, worldLabel, assets,
                gateDirective, priorChanges, List.of(), null);
    }

    String buildBatchUser(RunDO run, List<ChapterDO> chapters, List<Integer> window,
                          List<BeatInstanceDO> winBeats, Map<Integer, String> labelBySeq,
                          String worldLabel, JsonNode assets, String gateDirective,
                          JsonNode priorChanges,
                          List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule,
                          StoryContract contract) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        if (priorChanges != null && priorChanges.isArray() && !priorChanges.isEmpty()) {
            root.set("已发生不可逆状态（硬约束：这些人不得再有现场戏份，回忆/遗体/转述除外）",
                    priorChanges);
        }
        var head = root.putObject("全局头");
        head.put("protagonist", assets.path("protagonist").asText(""));
        head.set("cast", assets.path("cast"));
        head.set("brief", assets.path("brief"));
        var allowedNames = root.putArray("允许人名（逐字使用，禁止新造）");
        for (JsonNode c : assets.path("cast")) {
            String name = c.path("name").asText("").trim();
            if (!name.isEmpty()) {
                allowedNames.add(name);
            }
        }
        root.put("人名硬约束", "filled 和 draft_outline 中出现的每个有名人名都必须来自上表；"
                + "路人没有名字，不要给路人起名");
        root.set("角色叙事计划", characterPlanMapper == null ? STATIC_MAPPER.createArrayNode()
                : PlanJsonViews.characters(characterPlanMapper.selectList(
                new LambdaQueryWrapper<CharacterPlanDO>()
                        .eq(CharacterPlanDO::getRunId, run.getId())
                        .orderByAsc(CharacterPlanDO::getCharacterKey))));
        if (businessModelService != null) {
            root.set("角色业务模型", businessModelService.context(run.getId()));
        }
        root.set("线程计划", storyThreadMapper == null ? STATIC_MAPPER.createArrayNode()
                : PlanJsonViews.threads(storyThreadMapper.selectList(
                new LambdaQueryWrapper<StoryThreadDO>()
                        .eq(StoryThreadDO::getRunId, run.getId())
                        .orderByAsc(StoryThreadDO::getThreadKey))));
        ObjectNode keyMap = root.putObject("characterKeyMap");
        for (JsonNode c : assets.path("cast")) {
            String key = c.path("characterKey").asText("");
            if (!key.isBlank()) {
                keyMap.put(key, c.path("name").asText(""));
            }
        }
        if (assets.path("truth").isObject()) {
            head.set("truth", assets.path("truth"));
        }
        var prev = root.putArray("前批章纲概览");
        for (ChapterDO c : chapters) {
            if (!window.contains(c.getChapterNo())
                    && c.getDraftOutline() != null && !c.getDraftOutline().isBlank()) {
                var n = prev.addObject();
                n.put("no", c.getChapterNo());
                n.put("title", c.getTitle());
                n.put("draft_outline", c.getDraftOutline());
            }
        }
        var chArr = root.putArray("本窗章节计划");
        int lastChapterNo = chapters.isEmpty() ? 0 : chapters.get(chapters.size() - 1).getChapterNo();
        boolean containsFinalChapter = window.contains(lastChapterNo);
        VolumePlanDO volume = findVolume(run.getId(), window, chapters);
        if (volume != null) {
            ObjectNode volumeNode = root.putObject("本卷结构");
            volumeNode.put("volume_no", volume.getVolumeNo());
            volumeNode.put("from_chapter", volume.getFromChapter());
            volumeNode.put("to_chapter", volume.getToChapter());
            volumeNode.put("local_event", volume.getLocalEvent());
            volumeNode.put("local_closure", volume.getLocalClosure());
            volumeNode.put("next_hook", volume.getNextHook());
            volumeNode.put("global_role", volume.getGlobalRole());
            volumeNode.set("planContract", readTreeSafe(volume.getContract()));
            if (window.contains(volume.getToChapter())) {
                root.put("本卷末章要求",
                        "本窗口含本卷末章：必须完成本卷事件与局部闭合，写清本卷阶段真相、人物关系变化，"
                                + "并留下下一卷钩子；不要提前解决全书最终真相。");
            }
        }
        if ("闭合".equals(run.getParamOutcome()) && containsFinalChapter) {
            root.put("终章收束要求",
                    "本窗含全书终章。终章必须做三件事：总结当前局面、复盘主线真相、交代主要角色的后续；"
                            + "不得继续引入新的追捕/反转/未解困局，不得用悬念收尾。最后一拍必须落在稳定结局。");
        }
        Map<Integer, com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> scheduleByChapter =
                com.zzdzz.novelgen.derive.contract.ChapterScheduleProjector.byChapter(schedule);
        Map<String, StoryContract.Clue> clueByKey = new HashMap<>();
        if (contract != null) {
            contract.clues().forEach(clue -> clueByKey.put(clue.clueKey(), clue));
        }
        for (ChapterDO c : chapters) {
            if (window.contains(c.getChapterNo())) {
                var n = chArr.addObject();
                n.put("no", c.getChapterNo());
                n.put("title", c.getTitle());
                n.put("budget", c.getBudget());
                com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry entry =
                        scheduleByChapter.get(c.getChapterNo());
                mustArray(n, "mustPlant", entry == null ? List.of() : entry.mustPlant(), clueByKey);
                mustArray(n, "mustPayoff", entry == null ? List.of() : entry.mustPayoff(), clueByKey);
                n.put("reveal", entry != null && entry.reveal());
                n.put("culpritName", contract == null || contract.truth() == null
                        ? "" : contract.truth().culpritName());
                n.put("motiveTag", contract == null || contract.truth() == null
                        ? "" : contract.truth().motiveTag());
                n.set("planContract", readTreeSafe(c.getPlanContract()));
            }
        }
        var bArr = root.putArray("本窗拍清单");
        for (BeatInstanceDO b : winBeats) {
            var n = bArr.addObject();
            n.put("seq", b.getSeq());
            n.put("chapter", b.getChapterNo());
            n.put("beat_type", b.getBeatType());
            n.put("pacing", b.getPacing().toPlainString());
            n.put("storyLayer", b.getStoryLayer());
            n.put("beatRole", b.getBeatRole());
            n.set("routeContract", readTreeSafe(b.getRouteContract()));
            String label = b.getFilledText() != null && !b.getFilledText().isBlank()
                    ? b.getFilledText() : labelBySeq.getOrDefault(b.getSeq(), "");
            n.put("label", label);
        }
        return root.toString();
    }

    /** 每章 mustPlant/mustPayoff 的结构化输入（key + 合同线索文本 + ownerKey）。 */
    private static void mustArray(ObjectNode chapter, String field, List<String> keys,
                                  Map<String, StoryContract.Clue> clueByKey) {
        ArrayNode arr = chapter.putArray(field);
        for (String key : keys) {
            StoryContract.Clue clue = clueByKey.get(key);
            ObjectNode item = arr.addObject();
            item.put("key", key);
            item.put("text", clue == null || clue.clue() == null ? "" : clue.clue());
            item.put("ownerKey", clue == null || clue.ownerKey() == null ? "" : clue.ownerKey());
        }
    }

    /** 把本窗章的 clueEvents/closingActs 并入 chapters.plan_contract（读旧值再 merge）。 */
    static String mergePlanContractEvents(String planContract, JsonNode chapter) {
        JsonNode existing = readTreeSafe(planContract);
        ObjectNode node = existing.isObject()
                ? (ObjectNode) existing.deepCopy() : STATIC_MAPPER.createObjectNode();
        node.set("clueEvents", chapter.path("clueEvents").isArray()
                ? chapter.path("clueEvents").deepCopy() : STATIC_MAPPER.createArrayNode());
        node.set("closingActs", chapter.path("closingActs").isArray()
                ? chapter.path("closingActs").deepCopy() : STATIC_MAPPER.createArrayNode());
        node.set("romanceEvents", chapter.path("romanceEvents").isArray()
                ? chapter.path("romanceEvents").deepCopy() : STATIC_MAPPER.createArrayNode());
        return node.toString();
    }

    /**
     * ⑥b 日程硬校验：逐章 PLANT/PAYOFF 集合必须等于日程、禁重复/合同外 key；
     * 揭示章前不得出现 closingActs，揭示章必须覆盖 CULPRIT/MOTIVE/METHOD 且写出真凶姓名。
     *
     * @return 漂移问题（空=一致）；调用方在 LlmJson.ask 内抛 Bad 触发 3 轮喂回
     */
    static List<String> validateScheduleEvents(
            JsonNode out, List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule,
            StoryContract contract) {
        List<String> drift = new ArrayList<>();
        if (out == null || schedule == null || schedule.isEmpty()
                || contract == null || contract.truth() == null) {
            return drift;
        }
        Map<Integer, com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> byNo =
                com.zzdzz.novelgen.derive.contract.ChapterScheduleProjector.byChapter(schedule);
        int reveal = contract.truth().revealChapter();
        Set<String> allowedActs = Set.of("PLANT", "PAYOFF");
        Set<String> closingNeeded = Set.of("CULPRIT", "MOTIVE", "METHOD");
        for (JsonNode chapter : out.path("chapters")) {
            int no = chapter.path("no").asInt(-1);
            com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry entry = byNo.get(no);
            if (entry == null) {
                continue;
            }
            List<String> plants = new ArrayList<>();
            List<String> payoffs = new ArrayList<>();
            boolean duplicate = false;
            for (JsonNode event : chapter.path("clueEvents")) {
                String key = event.path("key").asText("").trim();
                String act = event.path("act").asText("").trim().toUpperCase();
                if (key.isEmpty() || !allowedActs.contains(act)) {
                    drift.add("CONTRACT_CLUE_DRIFT：第" + no + "章 clueEvents 有非法条目（key="
                            + key + " act=" + act + "）");
                    continue;
                }
                List<String> bucket = "PLANT".equals(act) ? plants : payoffs;
                if (bucket.contains(key)) {
                    duplicate = true;
                }
                bucket.add(key);
            }
            if (duplicate) {
                drift.add("CONTRACT_CLUE_DRIFT：第" + no + "章 clueEvents 有重复 act/key");
            }
            if (!new LinkedHashSet<>(plants).equals(new LinkedHashSet<>(entry.mustPlant()))) {
                drift.add("CONTRACT_CLUE_DRIFT：第" + no + "章 PLANT 必须等于日程 "
                        + entry.mustPlant() + "，实际 " + plants);
            }
            if (!new LinkedHashSet<>(payoffs).equals(new LinkedHashSet<>(entry.mustPayoff()))) {
                drift.add("CONTRACT_CLUE_DRIFT：第" + no + "章 PAYOFF 必须等于日程 "
                        + entry.mustPayoff() + "，实际 " + payoffs);
            }
            Set<String> acts = new LinkedHashSet<>();
            chapter.path("closingActs").forEach(node -> {
                String act = node.asText("").trim().toUpperCase();
                if (!act.isEmpty()) {
                    acts.add(act);
                }
            });
            if (reveal > 0 && no < reveal && !acts.isEmpty()) {
                drift.add("CONTRACT_CLUE_DRIFT：第" + no + "章在揭示章 " + reveal
                        + " 前写了 closingActs " + acts);
            }
            if (no == reveal) {
                if (!acts.equals(closingNeeded)) {
                    drift.add("CONTRACT_CLUE_DRIFT：揭示章第" + no + "章 closingActs 必须为 "
                            + closingNeeded + "，实际 " + acts);
                }
                // 结构化 closingActs 负责闭合字段校验；正文不再要求逐字包含合同标签。
            }
        }
        return drift;
    }

    private VolumePlanDO findVolume(long runId, List<Integer> window, List<ChapterDO> chapters) {
        if (volumePlanMapper == null) {
            return null;
        }
        Integer volumeNo = chapters.stream()
                .filter(c -> window.contains(c.getChapterNo()))
                .map(ChapterDO::getVolumeNo)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (volumeNo == null) {
            return null;
        }
        return volumePlanMapper.selectOne(new LambdaQueryWrapper<VolumePlanDO>()
                .eq(VolumePlanDO::getRunId, runId)
                .eq(VolumePlanDO::getVolumeNo, volumeNo)
                .last("LIMIT 1"));
    }

    private void fillCommon(ObjectNode root, RunDO run, String worldLabel) {
        root.set("域", readTreeSafe(run.getDomainSnapshot()));
        root.set("类别", readTreeSafe(run.getParamTags()));
        root.put("目标节奏", run.getParamPace().toPlainString());
        root.put("结局形状", run.getParamOutcome());
        root.put("产品形态", run.getParamForm());
        if (run.getParamIntent() != null && !run.getParamIntent().isBlank()) {
            root.put("题材意图", run.getParamIntent());
        }
        root.put("世界观", worldLabel);
        StoryContract contract = contractService == null ? null : contractService.load(run);
        if (contract != null && !contract.truth().finalTruth().isBlank()) {
            String promptContext = contractService.promptContext(contract);
            root.put("主线", contract.truth().finalTruth());
            root.set("FROZEN_CONTRACT（唯一合同：cast/真相/线索/线程/禁令，禁止改写）",
                    readTreeSafe(promptContext));
            root.set("TruthContract（唯一真相合同，冲突时以此为准）", readTreeSafe(promptContext));
        } else {
            JsonNode truthContract = readTreeSafe(run.getStepStatus())
                    .path("steps").path("TRUTH_CONTRACT");
            if (truthContract.isObject() && !truthContract.path("finalTruth").asText("").isBlank()) {
                root.put("主线", truthContract.path("finalTruth").asText(""));
                root.set("TruthContract（唯一真相合同，冲突时以此为准）", truthContract);
            } else {
                root.put("主线", run.getWelded() == null ? "" : run.getWelded());
            }
        }
    }

    /** 头 B 输入：已定人名表 + 意图/域格（不带章节计划，避免模型顺手写章纲）。 */
    String buildPersonaUser(RunDO run, JsonNode assets, String worldLabel, String gateDirective) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        root.put("主角", assets.path("protagonist").asText(""));
        root.set("已定人名表", assets.path("cast"));
        root.set("人物表角色与人设（persona 必须与 role 一致）", assets.path("cast"));
        return root.toString();
    }

    /** 头 C 输入：书名/主角/cast 人名 + 章节计划（真相卡的 reveal/planted 必须落在这些章号里）。 */
    String buildBriefUser(RunDO run, JsonNode assets, List<ChapterDO> chapters,
                          String worldLabel, String gateDirective) {
        ObjectNode root = STATIC_MAPPER.createObjectNode();
        fillCommon(root, run, worldLabel);
        if (gateDirective != null && !gateDirective.isBlank()) {
            root.put("上轮立项闸减法指令（重做时必须逐条执行）", gateDirective);
        }
        root.put("书名", assets.path("title").asText(""));
        root.put("主角", assets.path("protagonist").asText(""));
        if (run.getParamStyle() != null && !run.getParamStyle().isBlank()) {
            root.put("目标文风（premise 的氛围与描写红线必须服从它，不得另立腔调）", run.getParamStyle());
        }
        var names = root.putArray("cast 人名表");
        for (JsonNode c : assets.path("cast")) {
            names.add(c.path("name").asText(""));
        }
        var chArr = root.putArray("章节计划");
        for (ChapterDO c : chapters) {
            var n = chArr.addObject();
            n.put("no", c.getChapterNo());
            n.put("title", c.getTitle());
            n.put("budget", c.getBudget());
        }
        return root.toString();
    }

    String worldLabel(RunDO run) {
        if (run.getPickedWorldId() == null) {
            return "";
        }
        AssetWorldDO w = worldMapper.selectById(run.getPickedWorldId());
        if (w == null) {
            return "";
        }
        return w.getLabelText() == null ? w.getExtractedText() : w.getLabelText();
    }

    List<String> bannedNames(RunDO run) {
        if (!Boolean.TRUE.equals(run.getAntiRepro())) {
            return List.of();
        }
        return fingerprintMapper.selectList(new LambdaQueryWrapper<WorkFingerprintDO>()
                        .eq(WorkFingerprintDO::getKind, "PROTAGONIST_NAME"))
                .stream().map(WorkFingerprintDO::getValue).filter(v -> v != null && !v.isBlank()).toList();
    }

    /** 原生拍标签层（ARC_SEG 按 arc_id+seg_no 回查原生链）；⑥b-1 窗体输入用。 */
    Map<Integer, String> buildLabelBySeq(List<BeatInstanceDO> beats) {
        Map<Long, List<String>> nativeLabels = new HashMap<>();
        Map<Integer, String> labelBySeq = new HashMap<>();
        for (BeatInstanceDO b : beats) {
            if ("ASSET".equals(b.getSource()) && b.getAssetId() != null) {
                AssetBeatDO row = beatMapper.selectById(b.getAssetId());
                labelBySeq.put(b.getSeq(), row == null ? "" : nullToEmpty(row.getLabelText(), row.getExtractedText()));
                        } else if ("SYNTHETIC".equals(b.getSource())) {
                labelBySeq.put(b.getSeq(), syntheticLabel(b));
            } else if (b.getArcId() != null) {
                nativeLabels.computeIfAbsent(b.getArcId(), k -> {
                    List<AssetBeatDO> list = beatMapper.selectList(new LambdaQueryWrapper<AssetBeatDO>()
                            .eq(AssetBeatDO::getParentArcId, k)
                            .orderByAsc(AssetBeatDO::getId));
                    List<String> out = new ArrayList<>();
                    for (AssetBeatDO r : list) {
                        out.add(nullToEmpty(r.getLabelText(), r.getExtractedText()));
                    }
                    return out;
                });
                List<String> chain = nativeLabels.get(b.getArcId());
                int seg = b.getArcSegNo() == null ? -1 : b.getArcSegNo();
                labelBySeq.put(b.getSeq(), seg >= 0 && seg < chain.size() ? chain.get(seg) : "");
            }
        }
        return labelBySeq;
    }

    static String syntheticLabel(BeatInstanceDO beat) {
        JsonNode route = readTreeSafe(beat.getRouteContract());
        String goal = route.path("goal").asText("").trim();
        return goal.isEmpty()
                ? "系统补拍：推进本章新信息，禁止复述已有拍"
                : goal;
    }

    // ---- 解析校验 ----

    /** 推理体裁判定：tags 含 推理/悬疑 → truth 真相卡必出（v3.4）。 */
    static boolean reasoningGenre(String paramTags) {
        return paramTags != null && (paramTags.contains("推理") || paramTags.contains("悬疑"));
    }

    record HeadContext(List<Integer> chapterNos, List<String> banned, boolean reasoning) {
    }

    record BatchContext(List<Integer> windowSeqs, List<Integer> windowChapters,
                        Set<String> castNames, Map<Integer, Integer> seqChapters,
                        List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule) {
        BatchContext(List<Integer> windowSeqs, List<Integer> windowChapters) {
            this(windowSeqs, windowChapters, Set.of(), Map.of(), List.of());
        }

        BatchContext(List<Integer> windowSeqs, List<Integer> windowChapters,
                     Set<String> castNames) {
            this(windowSeqs, windowChapters, castNames, Map.of(), List.of());
        }

        BatchContext(List<Integer> windowSeqs, List<Integer> windowChapters,
                     Set<String> castNames, Map<Integer, Integer> seqChapters) {
            this(windowSeqs, windowChapters, castNames, seqChapters, List.of());
        }
    }

    /** state_changes.what 的封闭集合（不可逆变更，才需要跨窗传递）。 */
    static final Set<String> STATE_WHAT = Set.of(
            "死亡", "离场", "被囚", "失去身份", "关系改变", "物件易主", "得知");

    /** 头 A：定名与人物表（人名表是全书唯一合法名源，人设卡留到 B 段）。 */
    static JsonNode parseHeadBasic(JsonNode root, HeadContext ctx) {
        String title = root.path("title").asText("").trim();
        String summary = root.path("summary").asText("").trim();
        String protagonist = root.path("protagonist").asText("").trim();
        if (title.isEmpty() || summary.isEmpty() || protagonist.isEmpty()) {
            throw new LlmJson.Bad("title/summary/protagonist 均不能为空");
        }
        if (ctx.banned().contains(protagonist)) {
            throw new LlmJson.Bad("protagonist「" + protagonist + "」在禁用名单（历史成品已用），换一个");
        }
        JsonNode cast = root.path("cast");
        if (!cast.isArray() || cast.size() < 3) {
            throw new LlmJson.Bad("cast 至少 3 人");
        }
        Set<String> castNames = new HashSet<>();
        boolean castHasProtagonist = false;
        for (JsonNode c : cast) {
            String nm = c.path("name").asText("").trim();
            if (nm.isEmpty()) {
                throw new LlmJson.Bad("cast 有成员缺 name");
            }
            if (!castNames.add(nm)) {
                throw new LlmJson.Bad("cast 人名重复: " + nm);
            }
            if (nm.equals(protagonist)) {
                castHasProtagonist = true;
            }
            if (c.path("role").asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("cast 成员「" + nm + "」缺 role（身份一句话）");
            }
        }
        if (!castHasProtagonist) {
            throw new LlmJson.Bad("cast 必须包含 protagonist「" + protagonist + "」");
        }
        return root;
    }

    /** 头 B：人设卡（只准名单内的人，五键全填，不得漏人）。 */
    static JsonNode parsePersona(JsonNode root, JsonNode assets) {
        JsonNode arr = root.path("persona");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new LlmJson.Bad("必须输出 persona 数组");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode c : assets.path("cast")) {
            allowed.add(c.path("name").asText(""));
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode p : arr) {
            String nm = p.path("name").asText("").trim();
            if (!allowed.contains(nm)) {
                throw new LlmJson.Bad("persona 出现名单外人名「" + nm + "」——只许用 cast 表内人名");
            }
            if (!seen.add(nm)) {
                throw new LlmJson.Bad("persona 人名重复: " + nm);
            }
            for (String k : PERSONA_KEYS) {
                if (p.path(k).asText("").trim().isEmpty()) {
                    throw new LlmJson.Bad("「" + nm + "」缺 " + k);
                }
            }
        }
        List<String> missing = allowed.stream().filter(n -> !seen.contains(n)).toList();
        if (!missing.isEmpty()) {
            throw new LlmJson.Bad("persona 漏了名单内的人：" + missing);
        }
        return arr;
    }

    /**
     * 头 C：只校验 brief.premise（人物/世界观/氛围/红线摘要）。
     *
     * <p>真相卡已移出 ⑥b：truth 由冻结合同投影写入，LLM 输出里即便带了 truth 也不参与落库，
     * 故这里不再做 truth 三要素/clue 泄露/揭示章校验（那些方法保留给旧数据与单测）。
     */
    static JsonNode parseBriefTruth(JsonNode root, HeadContext ctx) {
        JsonNode brief = root.path("brief");
        if (!brief.isObject() || brief.path("premise").asText("").trim().isEmpty()) {
            throw new LlmJson.Bad("必须输出 brief.premise（人物/世界观/氛围/红线摘要，≤300字）");
        }
        return root;
    }

    /** 机械归一：模型重复/错挂 seq 时以装箱结果为准，禁止整轮停摆。 */
    static JsonNode normalizeBatchOutput(JsonNode root, BatchContext ctx) {
        if (!root.isObject()) {
            return root;
        }
        Map<Integer, List<ObjectNode>> candidatesBySeq = new LinkedHashMap<>();
        Map<Integer, Integer> planned = ctx.seqChapters();
        List<ObjectNode> sourceChapters = new ArrayList<>();
        root.path("chapters").forEach(ch -> {
            if (ch.isObject()) {
                sourceChapters.add((ObjectNode) ch);
            }
        });
        for (ObjectNode chapter : sourceChapters) {
            int no = chapter.path("no").asInt(-1);
            if (!ctx.windowChapters().contains(no)) {
                continue;
            }
            chapter.path("beats").forEach(raw -> {
                if (!raw.isObject()) {
                    return;
                }
                ObjectNode beat = (ObjectNode) raw;
                int seq = beat.path("seq").asInt(0);
                if (seq == 0 || !ctx.windowSeqs().contains(seq)) {
                    return;
                }
                Integer expected = planned.get(seq);
                beat.put("chapter_no", no);
                candidatesBySeq.computeIfAbsent(seq, key -> new ArrayList<>()).add(beat);
            });
        }
        ObjectNode normalized = root.deepCopy();
        normalized.putArray("chapters");
        com.fasterxml.jackson.databind.node.ArrayNode chapters =
                (com.fasterxml.jackson.databind.node.ArrayNode) normalized.path("chapters");
        Map<Integer, ObjectNode> byNo = new LinkedHashMap<>();
        for (Integer no : ctx.windowChapters()) {
            ObjectNode chapter = null;
            for (ObjectNode candidate : sourceChapters) {
                if (candidate.path("no").asInt(-1) == no) {
                    chapter = candidate.deepCopy();
                    break;
                }
            }
            if (chapter == null) {
                chapter = STATIC_MAPPER.createObjectNode();
                chapter.put("no", no);
                chapter.put("title", "第" + no + "章");
                chapter.put("draft_outline", "由本窗拍自动汇总");
                chapter.put("time", "当晚");
                chapter.putArray("state_changes");
            }
            chapter.putArray("beats");
            byNo.put(no, chapter);
            chapters.add(chapter);
        }
        Map<Integer, ObjectNode> beatsBySeq = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<ObjectNode>> entry : candidatesBySeq.entrySet()) {
            int seq = entry.getKey();
            Integer expected = planned.get(seq);
            ObjectNode chosen = entry.getValue().stream()
                    .filter(beat -> expected != null && expected == beat.path("chapter_no").asInt())
                    .findFirst().orElse(entry.getValue().get(0));
            log.warn("具体稿拍归属归一：seq={} 采用第{}章输出，忽略重复/错挂输出",
                    seq, chosen.path("chapter_no").asInt());
            beatsBySeq.put(seq, chosen);
        }
        for (ObjectNode beat : beatsBySeq.values()) {
            int seq = beat.path("seq").asInt(0);
            Integer no = planned.get(seq);
            if (no == null || !byNo.containsKey(no)) {
                no = beat.path("chapter_no").asInt(-1);
            }
            ObjectNode chapter = byNo.get(no);
            if (chapter != null) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) chapter.path("beats")).add(beat);
            }
        }
        return normalized;
    }
    static String batchPrompt(RunDO run) {
        if (run == null || !"ROMANCE".equalsIgnoreCase(run.getGenre())) {
            return BATCH_PROMPT;
        }
        return BATCH_PROMPT + """

                言情合同专项：
                - 每章输出 romanceEvents 数组；stage 只能取 RELATIONSHIP_START/MISUNDERSTANDING/ATTACHMENT_ESCALATION/CONFLICT_PEAK/CONFESSION/RECONCILIATION/RELATIONSHIP_CLOSURE；
                - romanceEvents[].targetKey/threadKey 必须引用合同关系线或合同角色；
                - 不得输出 clueEvents 或 closingActs；
                - romanceEvents[].evidence 写清关系推进事实，不得用目标 key 冒充证据。
                """;
    }

    static JsonNode parseBatch(JsonNode root, BatchContext ctx) {
        root = normalizeBatchOutput(root, ctx);
        JsonNode chArr = root.path("chapters");
        if (!chArr.isArray() || chArr.size() != ctx.windowChapters().size()) {
            throw new LlmJson.Bad("chapters 章数必须 = 本窗章数 " + ctx.windowChapters().size());
        }
        // 模型偶尔把已计划拍放到相邻章；按 seqChapters 确定性搬回计划章，避免整窗重试。
        if (root.path("chapters").isArray() && ctx.seqChapters() != null && !ctx.seqChapters().isEmpty()) {
            Map<Integer, ObjectNode> byNo = new HashMap<>();
            root.path("chapters").forEach(c -> byNo.put(c.path("no").asInt(-1), (ObjectNode) c));
            for (ObjectNode chapter : byNo.values()) {
                if (!chapter.path("beats").isArray()) {
                    continue;
                }
                List<JsonNode> moves = new ArrayList<>();
                chapter.path("beats").forEach(beat -> {
                    int seq = beat.path("seq").asInt(0);
                    Integer planned = ctx.seqChapters().get(seq);
                    if (planned != null && planned != chapter.path("no").asInt(-1)) {
                        moves.add(beat);
                    }
                });
                for (JsonNode beat : moves) {
                    com.fasterxml.jackson.databind.node.ArrayNode chapterBeats = (com.fasterxml.jackson.databind.node.ArrayNode) chapter.path("beats"); for (int idx = chapterBeats.size() - 1; idx >= 0; idx--) { if (chapterBeats.get(idx) == beat) { chapterBeats.remove(idx); break; } }
                    ObjectNode target = byNo.get(ctx.seqChapters().get(beat.path("seq").asInt(0)));
                    if (target != null) {
                        if (!target.path("beats").isArray()) {
                            target.putArray("beats");
                        }
                        ((com.fasterxml.jackson.databind.node.ArrayNode) target.path("beats")).add(beat);
                        log.warn("章纲拍归属归一：seq={} 移回第{}章", beat.path("seq").asInt(), ctx.seqChapters().get(beat.path("seq").asInt(0)));
                    }
                }
            }
        }

        Set<Integer> seenCh = new HashSet<>();
        Set<Integer> filled = new HashSet<>();
        for (JsonNode c : chArr) {
            int no = c.path("no").asInt(-1);
            if (!ctx.windowChapters().contains(no) || !seenCh.add(no)) {
                throw new LlmJson.Bad("chapters 章号非法或重复: " + no);
            }
            if (c.path("draft_outline").asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("第" + no + "章缺 draft_outline");
            }
            if (c.path("time").asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("第" + no + "章缺 time（本章时间跨度，如\"当晚\"\"三日后\"）");
            }
            JsonNode changes = c.path("state_changes");
            if (!changes.isArray()) {
                throw new LlmJson.Bad("第" + no + "章 state_changes 必须是数组（没有就写空数组）");
            }
            ArrayNode changeArray = (ArrayNode) changes;
            for (int changeIndex = changeArray.size() - 1; changeIndex >= 0; changeIndex--) {
                JsonNode ch = changeArray.get(changeIndex);
                String who = ch.path("who").asText("").trim();
                String what = ch.path("what").asText("").trim();
                if (who.isEmpty() || what.isEmpty()) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes 有条目缺 who/what");
                }
                if ("关系改变".equals(who) && "关系改变".equals(what)
                        && ch.path("detail").asText("").trim().isEmpty()) {
                    changeArray.remove(changeIndex);
                    continue;
                }
                if (!ctx.castNames().isEmpty() && !ctx.castNames().contains(who)) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes 的 who「" + who
                            + "」不在 cast 名单内");
                }
                if (!STATE_WHAT.contains(what)) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes.what「" + what
                            + "」不在允许集合 " + STATE_WHAT);
                }
                if ("得知".equals(what) && ch.path("detail").asText("").trim().isEmpty()) {
                    throw new LlmJson.Bad("第" + no + "章 state_changes「得知」必须用 detail 写知道了什么");
                }
            }
            for (JsonNode b : c.path("beats")) {
                int seq = b.path("seq").asInt(0);
                if (!ctx.windowSeqs().contains(seq)) {
                    continue;
                }
                if (!filled.add(seq)) {
                    throw new LlmJson.Bad("filled beats 含非法或重复 seq: " + seq);
                }
                Integer plannedChapter = ctx.seqChapters().get(seq);
                if (plannedChapter != null && plannedChapter != no) {
                    throw new LlmJson.Bad("seq " + seq + " 计划归属第" + plannedChapter
                            + "章，不能移动到第" + no + "章");
                }
                String filledText = b.path("filled").asText("").trim();
                if (filledText.isEmpty()) {
                    throw new LlmJson.Bad("seq " + seq + " 缺 filled");
                }
                if (isPlaceholderFilled(filledText)) {
                    throw new LlmJson.Bad("seq " + seq + " 的 filled 是占位文本（不落位/留待后续/待定），"
                            + "必须在本章写出具体人物动作与结果");
                }
                if (!ctx.castNames().isEmpty()
                        && ctx.castNames().stream().noneMatch(filledText::contains)) {
                    throw new LlmJson.Bad("seq " + seq + " 的 filled 没有出现 cast 内人物——"
                            + "必须写清谁做了什么、导致了什么结果；允许人名只能是："
                            + String.join("、", ctx.castNames()));
                }
            }
        }
        // skipped 是模型的辅助标注，不是第二份真源：filled 优先。
        // 模型偶尔会把已填实/重复/越界的 seq 也塞进 skipped，做确定性归一，
        // 只保留本窗且未填实的拍；最终是否完整仍由下面的覆盖校验硬拦。
        Set<Integer> skipped = new HashSet<>();
        for (JsonNode s : root.path("skipped")) {
            int seq = skippedSeq(s);
            if (seq == 0 || !ctx.windowSeqs().contains(seq) || filled.contains(seq)) {
                continue;
            }
            skipped.add(seq);
        }
        Set<Integer> union = new HashSet<>(filled);
        union.addAll(skipped);
        if (union.size() != ctx.windowSeqs().size()) {
            Set<Integer> missing = new LinkedHashSet<>(ctx.windowSeqs());
            missing.removeAll(union);
            throw new LlmJson.Bad("filled ∪ skipped 必须恰好覆盖本窗全部 " + ctx.windowSeqs().size()
                    + " 拍（当前 " + union.size() + "）；缺 seq=" + missing
                    + "，逐拍补进对应章的 beats，或明确列入 skipped");
        }
        JsonNode newNames = root.path("new_names");
        if (newNames.isArray() && !newNames.isEmpty()) {
            throw new LlmJson.Bad("禁止新造人名（new_names 必须为空）——改用全局头 cast 表内人名或把该拍放进 skipped");
        }
        return root;
    }

    /** skipped 兼容整数序号和 {seq,reason} 对象两种模型写法。 */
    static int skippedSeq(JsonNode node) {
        if (node == null) {
            return 0;
        }
        return node.isObject() ? node.path("seq").asInt(0) : node.asInt(0);
    }

    void validateObligationWindow(JsonNode root, BatchContext ctx, long runId) {
        if (obligationMapper == null) {
            return;
        }
        List<StoryObligation> obligations = loadObligations(runId);
        if (obligations.isEmpty()) {
            return;
        }
        DraftWindowObligationValidator validator = draftWindowObligationValidator == null
                ? new DraftWindowObligationValidator() : draftWindowObligationValidator;
        List<DraftWindowObligationValidator.ChapterDraft> chapters = new ArrayList<>();
        Map<Integer, List<DraftWindowObligationValidator.StateChange>> stateChanges = new HashMap<>();
        Map<Integer, List<DraftWindowObligationValidator.KnowledgeEvent>> knowledge = new HashMap<>();
        List<DraftWindowObligationValidator.BeatDraft> beats = new ArrayList<>();
        for (JsonNode chapter : root.path("chapters")) {
            int chapterNo = chapter.path("no").asInt(0);
            List<String> clueActs = new ArrayList<>();
            for (JsonNode event : chapter.path("clueEvents")) {
                String act = event.path("act").asText("").trim();
                String key = event.path("key").asText("").trim();
                if (!act.isEmpty() && !key.isEmpty()) {
                    clueActs.add(act.toUpperCase(java.util.Locale.ROOT) + ":" + key);
                }
            }
            List<DraftWindowObligationValidator.RomanceEvent> romanceEvents = new ArrayList<>();
            for (JsonNode event : chapter.path("romanceEvents")) {
                String stage = event.path("stage").asText("").trim();
                String target = event.path("targetKey").asText("").trim();
                String thread = event.path("threadKey").asText("").trim();
                String evidence = event.path("evidence").asText("").trim();
                if (!stage.isEmpty()) {
                    romanceEvents.add(new DraftWindowObligationValidator.RomanceEvent(
                            stage, target, thread, evidence));
                }
            }
            List<String> entities = new ArrayList<>();
            List<DraftWindowObligationValidator.StateChange> changes = new ArrayList<>();
            for (JsonNode change : chapter.path("state_changes")) {
                String who = change.path("who").asText("").trim();
                String what = change.path("what").asText("").trim();
                if (!who.isEmpty()) {
                    entities.add(who);
                    changes.add(new DraftWindowObligationValidator.StateChange(who, what,
                            change.path("detail").asText("")));
                }
            }
            stateChanges.put(chapterNo, changes);
            List<String> threads = new ArrayList<>();
            for (JsonNode beat : chapter.path("beats")) {
                JsonNode route = readRouteNode(beat.path("route"));
                for (JsonNode ref : route.path("threadRefs")) {
                    if (ref.isTextual() && !ref.asText("").isBlank()) {
                        threads.add(ref.asText(""));
                    }
                }
                List<DraftWindowObligationValidator.KnowledgeEvent> events =
                        knowledge.computeIfAbsent(chapterNo, key -> new ArrayList<>());
                addKnowledge(events, route.path("writesKnowledge"), "WRITE");
                addKnowledge(events, route.path("requiresKnowledge"), "READ");
                List<DraftWindowObligationValidator.ThreadEffect> threadEffects =
                        new ArrayList<>();
                for (JsonNode effect : route.path("threadEffects")) {
                    String key = effect.path("threadKey").asText(effect.path("key").asText("")).trim();
                    String value = effect.path("effect").asText("").trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        threadEffects.add(new DraftWindowObligationValidator.ThreadEffect(
                                key, value));
                    }
                }
                List<DraftWindowObligationValidator.KnowledgeEffect> knowledgeEffects =
                        new ArrayList<>();
                for (JsonNode effect : route.path("knowledgeEffects")) {
                    String key = effect.path("key").asText("").trim();
                    String kind = effect.path("kind").asText("").trim();
                    if (!key.isEmpty() && !kind.isEmpty()) {
                        knowledgeEffects.add(new DraftWindowObligationValidator.KnowledgeEffect(
                                kind, key));
                        addKnowledge(events, effect, kind);
                    }
                }
                beats.add(new DraftWindowObligationValidator.BeatDraft(
                        beat.path("seq").asInt(0), chapterNo, beat.path("role").asText(""),
                        textList(route.path("threadRefs")),
                        textList(route.path("actors")), textList(route.path("targets")),
                        threadEffects, knowledgeEffects, beat.path("filled").asText("")));
            }
            for (com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry entry : ctx.schedule()) {
                if (entry.chapterNo() == chapterNo) {
                    threads.addAll(entry.mustPlant());
                    threads.addAll(entry.mustPayoff());
                }
            }
            chapters.add(new DraftWindowObligationValidator.ChapterDraft(
                    chapterNo, chapter.path("draft_outline").asText(""), clueActs, entities,
                    threads, romanceEvents));
        }
        int from = ctx.windowChapters().isEmpty() ? 0 : ctx.windowChapters().get(0);
        int to = ctx.windowChapters().isEmpty() ? 0
                : ctx.windowChapters().get(ctx.windowChapters().size() - 1);
        DraftWindowObligationValidator.DraftWindow window =
                new DraftWindowObligationValidator.DraftWindow(from, to, chapters, beats,
                        stateChanges, knowledge, ctx.castNames());
        List<com.zzdzz.novelgen.derive.plan.PlanContracts.PlanIssue> issues =
                validator.validate(window, obligations);
        if (!issues.isEmpty()) {
            String detail = issues.stream()
                    .map(issue -> issue.code() + "：" + issue.message())
                    .collect(java.util.stream.Collectors.joining("；"));
            throw new LlmJson.Bad("OBLIGATION_WINDOW：" + detail);
        }
        persistEvidence(runId, validator.toEvidenceIndex(window), from, to);
    }

    private void persistEvidence(long runId, StoryEvidenceIndex index, int from, int to) {
        if (evidenceMapper == null || from <= 0 || to < from) {
            return;
        }
        evidenceMapper.deleteByRunAndChapterRange(runId, from, to);
        index.obligationsByChapter().forEach((chapterNo, rows) -> {
            if (chapterNo < from || chapterNo > to) {
                return;
            }
            for (StoryObligationEvidence evidence : rows) {
                DeriveStoryObligationEvidenceDO row = new DeriveStoryObligationEvidenceDO();
                row.setRunId(runId);
                row.setObligationKey(evidence.obligationKey());
                row.setChapterNo(evidence.chapterNo());
                row.setEvidenceKind(evidence.evidenceKind());
                row.setEventKey(evidence.eventKey());
                row.setBeatSeq(evidence.beatSeq());
                row.setEntityKeys(jsonList(evidence.entityKeys()));
                row.setThreadKeys(jsonList(evidence.threadKeys()));
                row.setFact(evidence.fact());
                row.setSource(evidence.source());
                row.setConfidence(evidence.confidence());
                evidenceMapper.insertFull(row);
            }
        });
    }

    private List<StoryObligation> loadObligations(long runId) {
        List<StoryObligation> out = new ArrayList<>();
        for (DeriveStoryObligationDO row : obligationMapper.selectByRun(runId)) {
            out.add(new StoryObligation(row.getObligationKey(), row.getChapterNo() == null
                    ? 0 : row.getChapterNo(), row.getKind(), row.getTargetKey(),
                    Boolean.TRUE.equals(row.getRequired()), Boolean.TRUE.equals(row.getForbidden()),
                    row.getSeverity(), row.getSourceContract(), readJsonObject(row.getPayload())));
        }
        return out;
    }

    private static Map<String, Object> readJsonObject(String json) {
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

    private static String jsonList(List<String> values) {
        try {
            return STATIC_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static void addKnowledge(List<DraftWindowObligationValidator.KnowledgeEvent> events,
                                     JsonNode values, String kind) {
        if (values == null) {
            return;
        }
        if (values.isTextual() && !values.asText("").isBlank()) {
            events.add(new DraftWindowObligationValidator.KnowledgeEvent(kind, values.asText("")));
        } else if (values.isArray()) {
            for (JsonNode value : values) {
                if (value.isTextual() && !value.asText("").isBlank()) {
                    events.add(new DraftWindowObligationValidator.KnowledgeEvent(kind, value.asText("")));
                }
            }
        }
    }

    private static JsonNode readRouteNode(JsonNode route) {
        if (route == null || route.isNull()) {
            return STATIC_MAPPER.createObjectNode();
        }
        if (route.isObject()) {
            return route;
        }
        if (route.isTextual() && !route.asText("").isBlank()) {
            return readTreeSafe(route.asText(""));
        }
        return STATIC_MAPPER.createObjectNode();
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText("").isBlank()) {
                out.add(item.asText(""));
            }
        });
        return out;
    }

    static boolean isPlaceholderFilled(String text) {
        return text.contains("不落位") || text.contains("留待后续") || text.contains("待后续")
                || text.contains("待定") || text.contains("占位") || text.contains("TODO")
                || text.equals("略") || text.equals("…") || text.equals("...");
    }

    /** 统计本窗产出的状态变更条数（日志用）。 */
    static int countChanges(JsonNode chapterArr) {
        int n = 0;
        for (JsonNode c : chapterArr) {
            n += c.path("state_changes").size();
        }
        return n;
    }

    /** ⑥b 已发生不可逆状态（跨窗硬约束）：读 run.step_status.steps.DRAFT.changes。 */
    static JsonNode readDraftState(String stepStatus) {
        JsonNode arr = readTreeSafe(stepStatus).path("steps").path("DRAFT").path("changes");
        return arr.isArray() ? arr : STATIC_MAPPER.createArrayNode();
    }

    /** 把本窗的 time/state_changes 累加进 step_status（保留 GATE/PLAN 等其它段）。 */
    static String appendDraftState(String stepStatus, JsonNode chapterArr) {
        JsonNode parsed = readTreeSafe(stepStatus);
        ObjectNode root = parsed.isObject() ? (ObjectNode) parsed.deepCopy() : STATIC_MAPPER.createObjectNode();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        ObjectNode draft = steps.path("DRAFT").isObject()
                ? (ObjectNode) steps.path("DRAFT") : steps.putObject("DRAFT");
        com.fasterxml.jackson.databind.node.ArrayNode arr = draft.path("changes").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) draft.path("changes")
                : draft.putArray("changes");
        for (JsonNode c : chapterArr) {
            int no = c.path("no").asInt(0);
            String time = c.path("time").asText("");
            for (JsonNode ch : c.path("state_changes")) {
                ObjectNode n = arr.addObject();
                n.put("no", no);
                n.put("time", time);
                n.put("who", ch.path("who").asText(""));
                n.put("what", ch.path("what").asText(""));
                if (!ch.path("detail").asText("").isBlank()) {
                    n.put("detail", ch.path("detail").asText(""));
                }
            }
        }
        return root.toString();
    }

    /** 清空 ⑥b 已发生状态（REWORK 回炉时整本重做，旧事实作废）。 */
    static String clearDraftState(String stepStatus) {
        JsonNode parsed = readTreeSafe(stepStatus);
        ObjectNode root = parsed.isObject() ? (ObjectNode) parsed.deepCopy() : STATIC_MAPPER.createObjectNode();
        root.put("v", 1);
        ObjectNode steps = root.path("steps").isObject()
                ? (ObjectNode) root.path("steps") : root.putObject("steps");
        ObjectNode draft = steps.putObject("DRAFT");
        draft.putArray("changes");
        return root.toString();
    }

    /**
     * 真相卡 solution 键名漂移归一化：模型偶发把 motive 漏个字母写成 mote、把 method 写成 means，
     * 三要素校验会误判成「没写动机」而连废 3 轮、整条全流程中断（run 29 实弹：1136 号调用，
     * 模型输出 culprit/mote/method，被判缺三要素→重试→FAILED 停摆 9 小时）。
     * 只补缺失键、不覆盖已有值；补完仍为空才交给 validateTruth 报错。
     */
    static void normalizeSolution(JsonNode root) {
        if (!(root.path("truth").path("solution") instanceof ObjectNode sol)) {
            return;
        }
        fillMissing(sol, "culprit", "killer", "criminal");
        fillMissing(sol, "motive", "mote", "motivation", "reason", "why");
        fillMissing(sol, "method", "means", "how", "way");
    }

    private static void fillMissing(ObjectNode target, String key, String... aliases) {
        if (!target.path(key).asText("").trim().isEmpty()) {
            return;
        }
        for (String alias : aliases) {
            String v = target.path(alias).asText("").trim();
            if (!v.isEmpty()) {
                target.put(key, v);
                return;
            }
        }
    }

    /** 实际出现的键名清单——报错带上它，省掉「到底缺哪个、模型拼成了什么」的二次取证。 */
    private static String keysOf(JsonNode obj) {
        if (!obj.isObject()) {
            return "非对象";
        }
        ArrayList<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        return String.join("/", keys);
    }

    /**
     * 真相卡问题清单（空=全合规）。收集而非早停——降级放行时要把毛病一次记全，
     * 否则模型修一个冒一个，3 轮白烧（run 29 实弹：reveal 冲突 / clue 泄底 / mote 拼写各占一轮）。
     */
    static List<String> truthIssues(JsonNode truth, List<Integer> chapterNos) {
        List<String> issues = new ArrayList<>();
        if (!truth.isObject()) {
            issues.add("未输出 truth 真相卡");
            return issues;
        }
        int reveal = truth.path("reveal_chapter").asInt(-1);
        boolean revealOk = chapterNos.contains(reveal);
        if (!revealOk) {
            issues.add("reveal_chapter 越界或缺失: " + reveal);
        }
        JsonNode sol = truth.path("solution");
        if (!sol.isObject()
                || sol.path("culprit").asText("").trim().isEmpty()
                || sol.path("motive").asText("").trim().isEmpty()
                || sol.path("method").asText("").trim().isEmpty()) {
            issues.add("solution 缺 culprit/motive/method 三要素（实际键：" + keysOf(sol) + "）");
        }
        JsonNode clues = truth.path("clues");
        if (!clues.isArray() || clues.size() < 3) {
            issues.add("clues 至少 3 条（当前 " + (clues.isArray() ? clues.size() : 0) + "）");
        } else {
            int maxPlanted = -1;
            for (JsonNode cl : clues) {
                int pc = cl.path("planted_chapter").asInt(-1);
                String clueText = cl.path("clue").asText("").trim();
                if (clueText.isEmpty() || !chapterNos.contains(pc)) {
                    issues.add("clues 有缺 clue 或 planted_chapter 越界: " + pc);
                    continue;
                }
                if (leaksSolution(clueText, sol)) {
                    issues.add("clue「" + clueText + "」复述了 solution 的动机/手法——只留可观测现象");
                }
                maxPlanted = Math.max(maxPlanted, pc);
            }
            if (revealOk && maxPlanted >= 0 && reveal < maxPlanted) {
                issues.add("reveal_chapter(" + reveal + ") 不得早于最晚埋设章(" + maxPlanted + ")");
            }
        }
        for (JsonNode a : truth.path("timeline_anchors")) {
            if (a.path("fact").asText("").trim().isEmpty()
                    || !chapterNos.contains(a.path("chapter").asInt(-1))) {
                issues.add("timeline_anchors 有缺 fact 或 chapter 越界");
            }
        }
        return issues;
    }

    /** 硬校验（有问题即抛第一条）——给需要 fail-hard 的调用方与单测。 */
    static void validateTruth(JsonNode truth, List<Integer> chapterNos) {
        List<String> issues = truthIssues(truth, chapterNos);
        if (!issues.isEmpty()) {
            throw new LlmJson.Bad(issues.get(0));
        }
    }

    /**
     * clue 泄底判据：点名凶手不算（「失踪罪人芬恩入狱前曾以吟游诗人身份登岛」是可观测事实，
     * 恰恰是好线索的写法），只有复述 solution 的动机/手法原文才算泄底。
     * 旧判据「clue 含 culprit 名即拦」把正确写法判成违规，模型被迫改写又撞上 reveal 约束（run 29 1134→1135 连锁）。
     */
    private static boolean leaksSolution(String clueText, JsonNode sol) {
        for (String key : List.of("motive", "method")) {
            String answer = sol.path(key).asText("").trim();
            if (sharesFragment(clueText, answer)) {
                return true;
            }
        }
        return false;
    }

    /** 8 字滑窗：答案里任一段原样出现在 clue 里即视为复述；答案本身不足 8 字不做判断。 */
    private static boolean sharesFragment(String clueText, String answer) {
        if (answer.length() < 8) {
            return false;
        }
        for (int i = 0; i + 8 <= answer.length(); i++) {
            if (clueText.contains(answer.substring(i, i + 8))) {
                return true;
            }
        }
        return false;
    }

    // ---- 窗与对账 ----

    /** 连续章段窗（确定性、不消耗 seed）：按章号升序切 size 段。 */
    static List<List<Integer>> windows(List<Integer> chapterNos, int size) {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < chapterNos.size(); i += size) {
            out.add(List.copyOf(chapterNos.subList(i, Math.min(i + size, chapterNos.size()))));
        }
        return out;
    }

    /** 同时按章数与拍数切窗；单章拍数再大也不拆章，保证 filled 不跨章移动。 */
    static List<List<Integer>> windowsByBeats(List<Integer> chapterNos,
                                              List<BeatInstanceDO> beats,
                                              int maxChapters, int maxBeats) {
        Map<Integer, Integer> countByChapter = new HashMap<>();
        for (BeatInstanceDO beat : beats) {
            if (beat.getChapterNo() != null) {
                countByChapter.merge(beat.getChapterNo(), 1, Integer::sum);
            }
        }
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int currentBeats = 0;
        for (Integer chapterNo : chapterNos) {
            int chapterBeats = countByChapter.getOrDefault(chapterNo, 0);
            if (!current.isEmpty() && (current.size() >= maxChapters
                    || currentBeats + chapterBeats > maxBeats)) {
                out.add(List.copyOf(current));
                current.clear();
                currentBeats = 0;
            }
            current.add(chapterNo);
            currentBeats += chapterBeats;
        }
        if (!current.isEmpty()) {
            out.add(List.copyOf(current));
        }
        return out;
    }

    /** 窗完成判定 = 窗内各章 draft_outline 非空（进度即库态，幂等依据）。 */
    static boolean windowDone(List<Integer> window, List<ChapterDO> chapters) {
        for (ChapterDO c : chapters) {
            if (window.contains(c.getChapterNo())
                    && (c.getDraftOutline() == null || c.getDraftOutline().isBlank())) {
                return false;
            }
        }
        return true;
    }

    /** 头完成判定 = story_assets.protagonist 非空（REWORK 清头后逼 ⑥b-0 重跑）。 */
    /** 全局头是否齐（三段全成；REWORK 清头产物后自然回到 0 段）。 */
    static boolean headDone(NovelDO novel) {
        return headStage(novel, false) >= 3;
    }

    /** 具体稿终局内容修复结果：需要回写库的章与拍 + derive_repair_log 留痕条目。 */
    record DraftRepairResult(List<ChapterDO> changedChapters,
                             List<BeatInstanceDO> changedBeats,
                             List<RepairEntry> repairs) {
    }

    /** 确定性修复留痕（Global Constraint：修复必须写 derive_repair_log）。 */
    record RepairEntry(String repairType, String before, String after, String reason) {
    }

    static DraftRepairResult normalizeDraftContent(List<ChapterDO> chapters,
                                                   List<BeatInstanceDO> beats) {
        return normalizeDraftContent(chapters, beats, List.of());
    }

    /**
     * 具体稿确定性归一。日程模式下 I1 拍章号不可变：只允许同章内语义去重，
     * 跳过终局拍负载归一；legacy 模式保留旧行为以兼容无日程历史 run。
     */
    static DraftRepairResult normalizeDraftContent(
            List<ChapterDO> chapters, List<BeatInstanceDO> beats,
            List<com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry> schedule) {
        boolean scheduleMode = schedule != null && !schedule.isEmpty();
        List<ChapterDO> changedChapters = new ArrayList<>();
        List<BeatInstanceDO> changedBeats = new ArrayList<>();
        List<RepairEntry> repairs = new ArrayList<>();
        Map<Integer, Integer> effectiveCount = new HashMap<>();
        for (BeatInstanceDO beat : beats) {
            if (beat.getChapterNo() != null && isFilled(beat)) {
                effectiveCount.merge(beat.getChapterNo(), 1, Integer::sum);
            }
        }
        for (int i = 0; i < beats.size(); i++) {
            BeatInstanceDO left = beats.get(i);
            if (!isFilled(left) || left.getChapterNo() == null
                    || isProtectedRole(left.getBeatRole())
                    || hasKnowledgeOrStateContract(left)) {
                continue;
            }
            for (int j = i + 1; j < beats.size(); j++) {
                BeatInstanceDO right = beats.get(j);
                if (!isFilled(right) || right.getChapterNo() == null
                        || isProtectedRole(right.getBeatRole())
                        || hasKnowledgeOrStateContract(right)
                        || "SKIPPED".equals(right.getStatus())) {
                    continue;
                }
                boolean mergeAllowed = scheduleMode
                        ? right.getChapterNo().intValue() == left.getChapterNo().intValue()
                        : (right.getChapterNo().intValue() == left.getChapterNo().intValue()
                        || Math.abs(right.getChapterNo() - left.getChapterNo()) == 1);
                if (mergeAllowed
                        && semanticSimilarity(left.getFilledText(), right.getFilledText()) >= 0.65
                        && effectiveCount.getOrDefault(right.getChapterNo(), 0) > 1) {
                    String beforeFill = right.getFilledText();
                    right.setStatus("SKIPPED");
                    changedBeats.add(right);
                    effectiveCount.merge(right.getChapterNo(), -1, Integer::sum);
                    repairs.add(new RepairEntry("DUPLICATE_BEAT",
                            "seq=" + right.getSeq() + "：" + beforeFill, "SKIPPED",
                            "与 seq=" + left.getSeq() + " 语义重复（相似度≥0.65）"));
                    log.warn("具体稿语义去重：seq={} 与 seq={} 重复，后拍跳过",
                            left.getSeq(), right.getSeq());
                    break;
                }
            }
        }
        for (int i = 1; i < chapters.size(); i++) {
            ChapterDO previous = chapters.get(i - 1);
            ChapterDO current = chapters.get(i);
            if (previous.getDraftOutline() == null || current.getDraftOutline() == null
                    || previous.getDraftOutline().isBlank()
                    || current.getDraftOutline().isBlank()) {
                continue;
            }
            if (semanticSimilarity(previous.getDraftOutline(), current.getDraftOutline()) < 0.65) {
                continue;
            }
            String rewritten = outlineFromBeats(beats, current.getChapterNo());
            if (rewritten == null || rewritten.isBlank()
                    || semanticSimilarity(previous.getDraftOutline(), rewritten) >= 0.65) {
                rewritten = previous.getDraftOutline() + "；本章转入后续取证与行动，" + current.getTitle();
            }
            String beforeOutline = current.getDraftOutline();
            current.setDraftOutline(rewritten);
            changedChapters.add(current);
            repairs.add(new RepairEntry("DUPLICATE_OUTLINE",
                    "第" + current.getChapterNo() + "章：" + beforeOutline,
                    "第" + current.getChapterNo() + "章：" + rewritten,
                    "与第" + previous.getChapterNo() + "章 outline 语义重复，已从本章拍重写"));
            log.warn("具体稿章纲去重：第{}章 outline 与第{}章重复并已重写",
                    current.getChapterNo(), previous.getChapterNo());
        }
        if (scheduleMode) {
            log.info("日程模式：跳过终局拍负载归一：chapters={} beats={}",
                    chapters == null ? 0 : chapters.size(), beats == null ? 0 : beats.size());
        } else {
            repairs.addAll(rebalanceBeatLoad(chapters, beats, changedBeats));
        }
        return new DraftRepairResult(changedChapters, changedBeats, repairs);
    }

    /**
     * P6 Task3 具体稿终局拍负载归一：单章有效拍超过
     * max(2, ceil(总有效拍/章数*1.5)) 时，只把非保护角色拍搬到欠载章；
     * seq 不变、每章至少 1 拍、route_contract.chapterNo 与章行同步。
     * 搬动的拍加入 changedBeats 由调用方 updateRoute 落库。
     */
    static List<RepairEntry> rebalanceBeatLoad(List<ChapterDO> chapters,
                                               List<BeatInstanceDO> beats,
                                               List<BeatInstanceDO> changedBeats) {
        List<RepairEntry> repairs = new ArrayList<>();
        if (chapters == null || chapters.size() < 2 || beats == null || beats.isEmpty()) {
            return repairs;
        }
        Map<Integer, Integer> load = new HashMap<>();
        for (BeatInstanceDO beat : beats) {
            if (beat.getChapterNo() != null && !"SKIPPED".equals(beat.getStatus())) {
                load.merge(beat.getChapterNo(), 1, Integer::sum);
            }
        }
        int total = load.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return repairs;
        }
        int max = Math.max(2, (int) Math.ceil(total * 1.5 / chapters.size()));
        Set<Integer> chapterNos = new HashSet<>();
        chapters.forEach(c -> chapterNos.add(c.getChapterNo()));
        while (true) {
            Integer source = load.entrySet().stream()
                    .filter(e -> e.getValue() > max)
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (source == null) {
                break;
            }
            Integer target = chapterNos.stream()
                    .filter(no -> !no.equals(source) && load.getOrDefault(no, 0) < max)
                    .min(Comparator.comparingInt(no -> load.getOrDefault(no, 0)))
                    .orElse(null);
            if (target == null) {
                break;
            }
            BeatInstanceDO candidate = null;
            for (BeatInstanceDO beat : beats) {
                if (beat.getChapterNo() != null && beat.getChapterNo() == source
                        && target >= source
                        && !"SKIPPED".equals(beat.getStatus())
                        && !isProtectedRole(beat.getBeatRole())
                        && !hasKnowledgeOrStateContract(beat)) {
                    candidate = beat;
                    break;
                }
            }
            if (candidate == null) {
                break;
            }
            int from = candidate.getChapterNo();
            candidate.setChapterNo(target);
            candidate.setRouteContract(retargetRouteContract(candidate.getRouteContract(), target));
            if (!changedBeats.contains(candidate)) {
                changedBeats.add(candidate);
            }
            load.merge(from, -1, Integer::sum);
            load.merge(target, 1, Integer::sum);
            repairs.add(new RepairEntry("BEAT_LOAD_REBALANCE",
                    "seq=" + candidate.getSeq() + " 第" + from + "章",
                    "第" + target + "章",
                    "单章有效拍超过上限 " + max + "，非保护角色拍搬移"));
            log.warn("终局拍负载归一：seq={} 从第{}章移至第{}章",
                    candidate.getSeq(), from, target);
        }
        return repairs;
    }

    /** 同步 route_contract.chapterNo，保持拍合同与章行一致。 */
    private static String retargetRouteContract(String routeContract, int chapterNo) {
        if (routeContract == null || routeContract.isBlank()) {
            return routeContract;
        }
        try {
            JsonNode node = STATIC_MAPPER.readTree(routeContract);
            if (!(node instanceof ObjectNode object)) {
                return routeContract;
            }
            object.put("chapterNo", chapterNo);
            return object.toString();
        } catch (Exception e) {
            return routeContract;
        }
    }

    static double semanticSimilarity(String left, String right) {
        String a = normalizeSemanticText(left);
        String b = normalizeSemanticText(right);
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        Set<String> leftShingles = shingles(a);
        Set<String> rightShingles = shingles(b);
        Set<String> intersection = new HashSet<>(leftShingles);
        intersection.retainAll(rightShingles);
        Set<String> union = new HashSet<>(leftShingles);
        union.addAll(rightShingles);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static String normalizeSemanticText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "").toLowerCase();
    }

    private static Set<String> shingles(String text) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            out.add(text.substring(i, i + 2));
        }
        return out;
    }

    private static boolean isFilled(BeatInstanceDO beat) {
        return beat.getFilledText() != null && !beat.getFilledText().isBlank();
    }

    /** 带线程/知识/状态契约的拍不可参与去重合并或负载搬移，避免时序与事实链断裂。 */
    private static boolean hasKnowledgeOrStateContract(BeatInstanceDO beat) {
        JsonNode route = readTreeSafe(beat.getRouteContract());
        return hasContractValue(route.path("threadRefs"))
                || hasContractValue(route.path("writesKnowledge"))
                || hasContractValue(route.path("requiresKnowledge"))
                || hasContractValue(route.path("changesState"))
                || hasContractValue(route.path("requiresState"))
                || hasContractValue(route.path("dependsOn"));
    }

    private static boolean hasContractValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return !node.isEmpty();
        }
        if (node.isObject()) {
            return !node.isEmpty();
        }
        if (node.isTextual()) {
            return !node.asText("").isBlank();
        }
        if (node.isNumber()) {
            return node.asDouble() != 0;
        }
        return node.isBoolean();
    }

    private static boolean isProtectedRole(String role) {
        if (role == null) {
            return false;
        }
        return Set.of("HOOK", "HIGHLIGHT", "CHARACTER_CHOICE", "COST",
                "LOCAL_CLOSURE", "FINAL_CLOSURE", "NEXT_HOOK", "FINAL_REVEAL",
                "CLUE_PLANT", "CLUE_PAYOFF", "RELATION_CONFLICT").contains(role);
    }

    private static String outlineFromBeats(List<BeatInstanceDO> beats, int chapterNo) {
        List<String> parts = new ArrayList<>();
        for (BeatInstanceDO beat : beats) {
            if (beat.getChapterNo() != null && beat.getChapterNo() == chapterNo
                    && isFilled(beat) && !"SKIPPED".equals(beat.getStatus())) {
                parts.add(beat.getFilledText().trim());
                if (parts.size() == 3) {
                    break;
                }
            }
        }
        return parts.isEmpty() ? "" : String.join("；", parts);
    }

    /** 窗口遗漏的结构拍由系统收口为 SKIPPED，避免模型漏标导致整本停摆。 */
    private void reconcileMissingBeats(long runId, List<BeatInstanceDO> beats) {
        for (BeatInstanceDO beat : beats) {
            boolean missing = beat.getFilledText() == null || beat.getFilledText().isBlank();
            if (missing && "PLANNED".equals(beat.getStatus())) {
                beat.setStatus("SKIPPED");
                beatInstanceMapper.updateFill(beat.getId(), beat.getFilledText(), beat.getStatus());
                log.warn("终局对账补跳过拍：runId={} seq={}", runId, beat.getSeq());
            }
        }
    }

    /** 终局对账：全拍 filled 或 SKIPPED、全章有纲。 */
    static void globalReconcile(List<BeatInstanceDO> beats, List<ChapterDO> chapters) {
        List<Integer> missingBeats = new ArrayList<>();
        for (BeatInstanceDO b : beats) {
            boolean filled = b.getFilledText() != null && !b.getFilledText().isBlank();
            boolean skipped = "SKIPPED".equals(b.getStatus());
            if (!filled && !skipped) {
                missingBeats.add(b.getSeq());
            }
        }
        List<Integer> missingCh = new ArrayList<>();
        for (ChapterDO c : chapters) {
            if (c.getDraftOutline() == null || c.getDraftOutline().isBlank()) {
                missingCh.add(c.getChapterNo());
            }
        }
        if (!missingBeats.isEmpty() || !missingCh.isEmpty()) {
            throw new LlmJson.Bad("终局对账失败：未填拍 seq=" + missingBeats + " 无纲章=" + missingCh);
        }
    }

    static String nullToEmpty(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    static JsonNode readTreeSafe(String json) {
        try {
            return STATIC_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return STATIC_MAPPER.createObjectNode();
        }
    }
}
