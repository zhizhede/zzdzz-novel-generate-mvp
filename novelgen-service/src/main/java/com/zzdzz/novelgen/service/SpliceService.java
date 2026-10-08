package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;
import com.zzdzz.novelgen.service.OutlineService.SceneSpec;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 多书拼接（SPLICE，阶段二四步链）：①跨书聚合素材池 → ②derive_splice 两层取样 + 结构校验
 * → ③前情种子（world_states chapter_no=0，防首章缝合断链）→ ④章行/场景全量预物化（生成走既有管线，
 * 章纲步见场景即跳过）。设计见 docs/改造方案-多模块与多书拼接.md §2.2 与取样增补。
 *
 * <p>拼接表出库前的把关 = 确定性结构校验（章号连续/来源在池内/跨源≥2/beats 非空/不超目标章数），
 * 校验失败以 {@link LlmJson.Bad} 喂回重试（≤2 轮）——语义级拼接审校节点随探针验收补（见 STATUS）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpliceService {

    private final NovelDataService novelData;
    private final SamplePlotNodeDataService plotData;
    private final ImportedSampleDataService sampleData;
    private final CanonDocDataService canonData;
    private final ChapterDataService chapterData;
    private final WorldStateDataService worldStateData;
    private final StylePackDataService stylePackData;
    private final OutlineService outlineService;
    private final LlmPort llm;
    private final LlmJson llmJson;
    private final PromptTemplateService promptTemplates;
    private final ObjectMapper mapper;

    /** 统一设定 + 大纲 + 前情种子 + 逐章拼接表（含两层取样来源）。 */
    record SpliceDraft(String settings, String outline, SpliceSeed seed, List<SpliceChapter> chapters) {
    }

    record SpliceSeed(String time, String summary) {
    }

    /** 一格拼接表：来源（sampleId/seq）= 小框架取样地址；vol/arc = 大框架槽位。 */
    record SpliceChapter(int no, long sampleId, int seq, int vol, String arc, String title,
                         String goal, String hook, String timeNote, String beatsJson) {
    }

    record SpliceResult(int chapters, int sources) {
    }

    /** 候选素材每样本上限（控制提示词体积）与全书候选总量上限。 */
    private static final int QUOTA_FLOOR = 6;
    private static final int POOL_CAP = 60;
    /** 单批取样章数上限：10 章批在探针中实证可出；更大单发出流会被 RST_STREAM 掐断（任务 123 实弹）。 */
    private static final int BATCH_SIZE = 10;

    /**
     * 四步链主体。targetHint＝目标章数（任务 toChapter 传入；≤0 时回落 derive_config.targetChapters，再缺省 40）。
     */
    public SpliceResult run(long novelId, int targetHint, BiConsumer<Integer, String> progress) {
        Recipe recipe = Recipe.parse(novelData.findDeriveConfig(novelId));
        if (recipe.preset() != Recipe.Preset.SPLICE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "本书不是拼接配方（preset≠SPLICE），无法执行拼接任务");
        }
        List<Long> sampleIds = recipe.samples();
        if (sampleIds.size() < 2) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "拼接需要至少 2 本取材样本（sources.samples）");
        }
        int cap = targetHint > 0 ? targetHint
                : (recipe.params().targetChapters() != null && recipe.params().targetChapters() > 0
                        ? recipe.params().targetChapters() : 40);

        // ① 摘取：跨书聚合章级素材池
        progress.accept(0, "取样：聚合跨书章级素材");
        List<SamplePlotNodeDO> pool = plotData.listChapterNodesBySamples(sampleIds);
        if (pool.isEmpty()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "所选样本没有章级剧情节点——先对每本样本跑「深度解析」出章级剧情");
        }
        Map<Long, Set<Integer>> poolIndex = new LinkedHashMap<>();
        for (SamplePlotNodeDO n : pool) {
            poolIndex.computeIfAbsent(n.getSampleId(), k -> new HashSet<>()).add(n.getSeq());
        }
        Map<Long, String> titles = new LinkedHashMap<>();
        for (Long id : sampleIds) {
            com.zzdzz.novelgen.model.entity.ImportedSampleDO s = sampleData.getById(id);
            titles.put(id, s == null || s.getTitle() == null || s.getTitle().isBlank()
                    ? "样本" + id : s.getTitle());
        }
        String poolText = poolDigest(pool, titles, sampleIds.size(), cap);

        // ② 拼接：derive_splice 分批两层取样（单批 ≤10 章——10 章批实证可出，40 章单发出流被 RST_STREAM 掐断；
        //    首批带 settings/outline/seed，后续批只出 chapters 并带衔接章目；每批结构校验失败喂回重试 ≤2）
        String skeletons = bookSkeletons(sampleIds, titles);
        int batches = (cap + BATCH_SIZE - 1) / BATCH_SIZE;
        List<SpliceChapter> allChapters = new ArrayList<>();
        SpliceDraft head = null;
        for (int b = 0; b < batches; b++) {
            int fromNo = b * BATCH_SIZE + 1;
            int expect = Math.min(BATCH_SIZE, cap - fromNo + 1);
            final boolean isFirstBatch = b == 0;
            final int batchFrom = fromNo;
            final int batchExpect = expect;
            progress.accept(0, "拼接：第 " + (b + 1) + "/" + batches + " 批（第 "
                    + fromNo + "-" + (fromNo + expect - 1) + " 章）");
            String user = buildBatchUser(recipe, cap, titles, skeletons, poolText,
                    fromNo, expect, b, batches, allChapters);
            SpliceDraft part = llmJson.ask(
                    new LlmPort.ChatRequest(LlmNode.DERIVE_SPLICE, novelId, null,
                            List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.DERIVE_SPLICE, "system")),
                                    LlmPort.Message.user(user)),
                            LlmTemps.VOLUME_PLAN),
                    node -> parseDraftRange(node, sampleIds, poolIndex, batchFrom, batchExpect, isFirstBatch),
                    2);
            if (isFirstBatch) {
                head = part;
            }
            allChapters.addAll(part.chapters());
        }
        SpliceDraft draft = new SpliceDraft(head.settings(), head.outline(), head.seed(),
                List.copyOf(allChapters));

        // ② 落库：统一设定 + 全书大纲（SPLICE 预设下 activate 不拦骨架前缀）
        progress.accept(0, "落库：统一设定与大纲");
        upsertCanon(novelId, "misc", "拼接设定", draft.settings());
        upsertCanon(novelId, "misc", "大纲", draft.outline());
        upsertCanon(novelId, "misc", "拼接表", provenanceTable(draft));

        // ④ 全量预物化：逐章 insertPlan + beats→场景（章纲步见场景即跳过，方向被钉死）
        int[] band = budgetBand(novelId);
        int perVol = recipe.params().chaptersPerVolume() != null
                ? Math.max(3, Math.min(30, recipe.params().chaptersPerVolume())) : 10;
        int done = 0;
        for (SpliceChapter ch : draft.chapters()) {
            chapterData.insertPlan(novelId, ch.no(), ch.vol() > 0 ? ch.vol() : (ch.no() - 1) / perVol + 1,
                    ch.arc(), ch.title(), ch.goal(), ch.hook(), ch.timeNote(), null, null, band[0], band[1]);
            ChapterDO c = chapterData.find(novelId, ch.no()).orElse(null);
            if (c != null) {
                List<SceneSpec> specs = NovelService.beatsToScenes(ch.beatsJson(), band[0]);
                if (!specs.isEmpty()) {
                    outlineService.materializeScenes(c.getId(), specs, false);
                }
            }
            done++;
            if (done % 5 == 0 || done == draft.chapters().size()) {
                progress.accept(ch.no(), "拼接落库 " + done + "/" + draft.chapters().size());
            }
        }

        // ③ 前情种子：world_states 置于 chapter_no=0（首章读取链 chapter_no<1 命中，防「无前情」占位断链）
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("time", draft.seed().time());
        seed.put("summary", draft.seed().summary());
        seed.put("note", "前情种子（SPLICE 拼接开篇前的既成事实）");
        worldStateData.upsert(novelId, 0, seed);

        log.info("拼接完成：novelId={} 取样来源 {} 本 / 预物化 {} 章（band {}-{}）",
                novelId, sampleIds.size(), done, band[0], band[1]);
        return new SpliceResult(done, sampleIds.size());
    }

    /** 素材池摘要：每样本按配额裁剪，控提示词体积（书名标签+章题+摘要+beats 截断）。 */
    private String poolDigest(List<SamplePlotNodeDO> pool, Map<Long, String> titles, int sourceCount, int cap) {
        int quota = Math.max(QUOTA_FLOOR, cap / Math.max(1, sourceCount) + 4);
        quota = Math.min(quota, Math.max(QUOTA_FLOOR, POOL_CAP / Math.max(1, sourceCount)));
        Map<Long, List<SamplePlotNodeDO>> bySample = new LinkedHashMap<>();
        for (SamplePlotNodeDO n : pool) {
            bySample.computeIfAbsent(n.getSampleId(), k -> new ArrayList<>()).add(n);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, List<SamplePlotNodeDO>> e : bySample.entrySet()) {
            String title = titles.getOrDefault(e.getKey(), "样本" + e.getKey());
            List<SamplePlotNodeDO> nodes = e.getValue();
            for (int i = 0; i < nodes.size() && i < quota; i++) {
                SamplePlotNodeDO n = nodes.get(i);
                sb.append("【样本").append(e.getKey()).append("《").append(title).append("》·章")
                        .append(n.getSeq()).append("】")
                        .append(n.getTitle() == null ? "" : n.getTitle()).append('\n')
                        .append(truncate(n.getSummary(), 240)).append('\n')
                        .append("beats: ").append(truncate(n.getBeats(), 700)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 各取材样本的书级骨架（大纲走线/世界观参考）；无解析则跳过。 */
    private String bookSkeletons(List<Long> sampleIds, Map<Long, String> titles) {
        StringBuilder sb = new StringBuilder();
        for (Long id : sampleIds) {
            plotData.listBySample(id).stream()
                    .filter(n -> "book".equals(n.getLevel())
                            && n.getSummary() != null && !n.getSummary().isBlank())
                    .findFirst()
                    .ifPresent(n -> sb.append("《").append(titles.get(id)).append("》：")
                            .append(truncate(n.getSummary(), 800)).append('\n'));
        }
        return sb.toString();
    }

    /** 单批用户消息：指定逻辑 + 骨架参考 + 本批范围（分批模式）+ 上批衔接章目 + 素材池。 */
    private String buildBatchUser(Recipe recipe, int cap, Map<Long, String> titles, String skeletons,
                                  String poolText, int fromNo, int expect, int batchIndex, int batches,
                                  List<SpliceChapter> done) {
        StringBuilder sb = new StringBuilder()
                .append("【指定逻辑（用户配方，最高优先级：世界观、大纲走线、小剧情线插入等按它执行，其余自由发挥）】\n")
                .append(recipe.params().pacingNote() == null || recipe.params().pacingNote().isBlank()
                        ? "（未填，按素材池自由发挥）" : recipe.params().pacingNote().strip())
                .append("\n\n目标章数：").append(cap)
                .append("\n题材标签：")
                .append(recipe.tags().isEmpty() ? "（未设，按素材池自定）" : String.join("、", recipe.tags()))
                .append("\n主视角人物：")
                .append(recipe.params().povCharacter() == null ? "（未设，自行定名并入名表）" : recipe.params().povCharacter());
        if (!skeletons.isEmpty()) {
            sb.append("\n\n【样本书级骨架参考（仅供大纲走线与世界观取材，禁止整段照抄）】\n").append(skeletons);
        }
        sb.append("\n\n【本批范围】全书共 ").append(batches).append(" 批，这是第 ").append(batchIndex + 1)
                .append(" 批：只输出第 ").append(fromNo).append("–").append(fromNo + expect - 1)
                .append(" 章，chapters 必须恰好 ").append(expect).append(" 条");
        if (batchIndex > 0) {
            sb.append("；本批 JSON **只含 chapters 数组**（settings/outline/seed 首批已给，不要重复）");
        }
        sb.append("。");
        if (!done.isEmpty()) {
            sb.append("\n【上一批章目（衔接用，勿重复）】\n");
            int start = Math.max(0, done.size() - 6);
            for (int i = start; i < done.size(); i++) {
                SpliceChapter c = done.get(i);
                sb.append("第").append(c.no()).append("章 ").append(c.title())
                        .append(" — ").append(truncate(c.goal(), 40)).append('\n');
            }
            SpliceChapter last = done.get(done.size() - 1);
            sb.append("上一批末章 hook：").append(truncate(last.hook(), 60)).append('\n');
        }
        sb.append("\n【素材池（章级；标签=样本id《书名》·章序，**sampleId/seq 一律照抄标签里的数字，禁止自造**）】\n").append(poolText);
        return sb.toString();
    }

    /**
     * 拼接表结构校验（出库前把关）：不过抛 {@link LlmJson.Bad} 喂回模型重写（ask 重试 ≤2）。
     * 包内可见供单测直驱。
     */
    static SpliceDraft parseDraft(JsonNode root, List<Long> allowed, Map<Long, Set<Integer>> poolIndex, int cap) {
        return parseDraftRange(root, allowed, poolIndex, 1, cap, true);
    }

    /** 分批版校验：章号从 fromNo 起恰好 expectCount 条；headRequired=false 时省略 settings/outline/seed。 */
    static SpliceDraft parseDraftRange(JsonNode root, List<Long> allowed, Map<Long, Set<Integer>> poolIndex,
                                       int fromNo, int expectCount, boolean headRequired) {
        String settings = root.path("settings").asText("").strip();
        String outline = root.path("outline").asText("").strip();
        String seedTime = root.path("seed").path("time").asText("").strip();
        String seedSummary = root.path("seed").path("summary").asText("").strip();
        if (headRequired) {
            if (settings.isEmpty()) {
                throw new LlmJson.Bad("settings 为空——首批必须给出统一名表/时间线/世界规则");
            }
            if (outline.isEmpty()) {
                throw new LlmJson.Bad("outline 为空——首批必须给出全书大纲");
            }
            if (seedSummary.isEmpty()) {
                throw new LlmJson.Bad("seed.summary 为空——前情种子是必填（防首章缝合断链）");
            }
        }
        JsonNode arr = root.path("chapters");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new LlmJson.Bad("chapters 缺失或为空");
        }
        if (arr.size() != expectCount) {
            throw new LlmJson.Bad("本批应恰好输出 " + expectCount + " 章（第 " + fromNo + "–"
                    + (fromNo + expectCount - 1) + "），实际 " + arr.size()
                    + " 章——分批范围必须精确，不要多给或少给");
        }
        Set<Long> usedSamples = new HashSet<>();
        List<SpliceChapter> chapters = new ArrayList<>();
        int expect = fromNo;
        for (JsonNode c : arr) {
            int no = c.path("no").asInt(-1);
            if (no != expect) {
                throw new LlmJson.Bad("章号必须从 " + fromNo + " 连续递增，第 " + expect + " 位却是 " + no);
            }
            expect++;
            long sampleId = c.path("sampleId").asLong(-1);
            int seq = c.path("seq").asInt(-1);
            if (!allowed.contains(sampleId)) {
                throw new LlmJson.Bad("第 " + no + " 章来源 sampleId=" + sampleId + " 不在取材清单内");
            }
            Set<Integer> seqs = poolIndex.get(sampleId);
            if (seqs == null || !seqs.contains(seq)) {
                throw new LlmJson.Bad("第 " + no + " 章来源 (" + sampleId + ",seq=" + seq + ") 不在素材池内——禁止编造");
            }
            usedSamples.add(sampleId);
            String goal = c.path("goal").asText("").strip();
            if (goal.isEmpty()) {
                throw new LlmJson.Bad("第 " + no + " 章 goal 为空");
            }
            JsonNode beats = c.path("beats");
            if (!beats.isArray() || beats.isEmpty()) {
                throw new LlmJson.Bad("第 " + no + " 章 beats 为空——小框架必须给节拍表");
            }
            chapters.add(new SpliceChapter(no, sampleId, seq, c.path("vol").asInt(0),
                    c.path("arc").asText("拼接卷"), c.path("title").asText(""), goal,
                    c.path("hook").asText(""), c.path("timeNote").asText(""), beats.toString()));
        }
        if (usedSamples.size() < 2) {
            throw new LlmJson.Bad("跨源取样不足：本批只用了 " + usedSamples.size() + " 个样本来源，至少 2 个（防单源复刻）");
        }
        return new SpliceDraft(settings, outline, new SpliceSeed(seedTime, seedSummary), chapters);
    }

    /** 拼接表 provenance（方案 §2.2 增补：两层取样来源必须分层落库）——大框架=卷槽位，小框架=(样本,章序)。 */
    static String provenanceTable(SpliceDraft draft) {
        StringBuilder sb = new StringBuilder("# 拼接表（两层取样来源 provenance）\n\n")
                .append("| 章 | 样本 | 样本章序 | 卷 |\n|---|---|---|---|\n");
        for (SpliceChapter ch : draft.chapters()) {
            sb.append("| ").append(ch.no()).append(" | 样本").append(ch.sampleId())
                    .append(" | 第").append(ch.seq()).append("章 | ").append(ch.arc()).append(" |\n");
        }
        sb.append("\n> 由 derive_splice 自动记录；大框架=卷槽位，小框架=(样本,章序) 取样地址。");
        return sb.toString();
    }

    private void upsertCanon(long novelId, String kind, String name, String content) {
        Long id = canonData.findId(novelId, kind, name);
        if (id == null) {
            canonData.insert(novelId, kind, name, content);
        } else {
            canonData.updateContent(id, content);
        }
    }

    /** 章长带取本书风格包 gate_config（budget_min/max，读口 DeriveSupport.readBudgetBand），拿不到用默认 1500-4500。 */
    private int[] budgetBand(long novelId) {
        int[] band = DeriveSupport.readBudgetBand(stylePackData.findGateConfigByNovel(novelId));
        return band != null ? band : new int[]{1500, 4500};
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
