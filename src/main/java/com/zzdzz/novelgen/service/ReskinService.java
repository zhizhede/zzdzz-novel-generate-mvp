package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 剧情换皮（derive_config.mode=RESKIN）：**保住剧情骨架，把人名/场景/职业/实体/结局执行者全部随机重造**。
 *
 * <p>与 MIGRATE（原样迁移）的区别：MIGRATE 连人名地名一起搬，只做可选的主角改名；RESKIN 连世界观都不沿用，
 * 只按样本的节拍表（谁想做什么/遇到谁/对方提出什么/如何凑人/结果如何）重写一套全新外衣。
 *
 * <p>为什么是**队列任务**而不是建书时同步跑：每一步都要调 LLM（1 次换皮设定 + 每章 1 次），
 * 十几章就是十几分钟，同步跑会把 HTTP 请求挂死。
 *
 * <p>为什么换皮设定要**先定一次再逐章用**：全书必须共用同一套外衣（同一个人名、同一个世界），
 * 否则每章各换各的会拼不成一本书。设定落 canon（kind=misc/name=换皮设定）留档、可复查、可复用。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReskinService {

    private final NovelDataService novelData;
    private final CanonDocDataService canonData;
    private final ChapterDataService chapterData;
    private final SamplePlotNodeDataService plotData;
    private final ImportedSampleDataService sampleData;
    /** 换皮人名表落成素材卡（场景注入来源）。 */
    private final MaterialCardDataService cardData;
    private final PromptTemplateService promptTemplates;
    private final LlmJson llmJson;
    private final OutlineService outlineService;
    private final ObjectMapper mapper;

    /** 换皮设定在 canon 里的落库位置（一本书一份，重跑覆盖）。 */
    public static final String SKIN_KIND = "misc";
    public static final String SKIN_NAME = "换皮设定";

    /** 样本章格没有机器可读的时间跨度，只能让换皮模型从原章摘要里自己推——推不出就照实说，别默认「次日」。 */
    static final String SAMPLE_TIME_HINT =
            "原章未标注，请从原章摘要与节拍里的时间线索判断（如「多年后」「冬去春来」「孩子已能走路」）；"
                    + "推不出来就照剧情推进估一个量级并写明依据，严禁默认按「紧接上一章」处理";

    /** 换皮结果：换了多少章、多少节拍。 */
    public record ReskinResult(int chapters, int beats) {
    }

    /**
     * 跑完整套换皮：①定新外衣（题材/世界/主角/大纲）②逐章把样本节拍换成新外衣并物化场景。
     *
     * @param progress 每章回调 (chapterNo, 人话消息)，供队列任务上报进度；可为 null
     */
    public ReskinResult run(long novelId, int fromNo, int toNo, BiConsumer<Integer, String> progress) {
        DeriveSupport.Cfg cfg = DeriveSupport.parse(novelData.findDeriveConfig(novelId));
        Long sampleId = cfg.sourceSampleId();
        if (sampleId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本书没有关联样本，无法换皮（derive_config.sourceSampleId 为空）");
        }
        List<SamplePlotNodeDO> chapters = plotData.listBySample(sampleId).stream()
                .filter(n -> "chapter".equals(n.getLevel()))
                .sorted(Comparator.comparingInt(SamplePlotNodeDO::getSeq))
                .toList();
        if (chapters.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "样本没有章级剧情节点（未深度解析出剧情），换皮无从下手——先去素材库深度解析该样本");
        }
        String sampleTitle = sampleData.getById(sampleId) == null ? "" : sampleData.getById(sampleId).getTitle();

        // ① 换皮设定（全书共用一套）：随机种子直接取时间戳尾数，落地留档
        String seed = String.valueOf(System.currentTimeMillis() % 1_000_000);
        String skin = generateSkin(novelId, sampleTitle, cfg.tags(), chapters, seed);
        upsert(novelId, SKIN_KIND, SKIN_NAME, skin);
        String skinOutline = text(skin, "outline");
        String skinWorld = text(skin, "world");
        if (!skinOutline.isBlank()) {
            upsert(novelId, "misc", "大纲", skinOutline);
        }
        if (!skinWorld.isBlank()) {
            upsert(novelId, "world", "世界观", skinWorld);
        }
        // 换皮设定里的 characters 是本书**唯一合法人名表**：落成素材卡（场景按别名/常驻注入，
        // 也给了「不得新造人名」一个可见来源），并把主角名回填 povCharacter，
        // 否则场景提示词里的视角人物还是「（未指定）」，模型会自由发挥人名。
        int cards = bindCharacters(novelId, skin);
        bindPovCharacter(novelId, skin);
        log.info("剧情换皮·换皮设定完成：novelId={} 题材={} 主角={} 人名表 {} 张卡",
                novelId, text(skin, "genre"), text(skin, "protagonist"), cards);

        // ② 逐章换皮：第 i 章（1 起）对应样本第 i 个章级节点
        int beats = 0;
        int done = 0;
        for (int no = fromNo; no <= toNo && no <= chapters.size(); no++) {
            SamplePlotNodeDO node = chapters.get(no - 1);
            if (progress != null) {
                progress.accept(no, "换皮第 " + no + "/" + Math.min(toNo, chapters.size()) + " 章");
            }
            JsonNode rewritten = rewriteChapter(novelId, no, skin, node.getSummary(), node.getBeats());
            String summary = rewritten.path("summary").asText("").strip();
            String hook = rewritten.path("hook").asText("").strip();
            if (summary.isEmpty()) {
                throw new BizException(ErrorCode.LLM_OUTPUT_INVALID,
                        "第 " + no + " 章换皮输出为空（llm_call_log node=derive_reskin 可回放）");
            }
            // 时间跨度必须落库：样本章格动辄跨年，全压成「次日」会让年龄/子嗣/技艺全线对不上，
            // 且 time_note 是 digest 时间锚点的唯一来源（缺它则世界状态的 time 全靠抽，易漂）。
            String timeNote = rewritten.path("time_note").asText("").strip();
            final int chNo = no;
            ChapterDO ch = chapterData.find(novelId, chNo)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                            "第 " + chNo + " 章章行不存在（换皮要求建书时已按样本结构建好章行）"));
            chapterData.updatePlan(ch.getId(), ch.getVolumeNo(), ch.getArc(), ch.getTitle(),
                    summary, hook.isEmpty() ? null : truncate(hook, 250),
                    timeNote.isEmpty() ? ch.getTimeNote() : truncate(timeNote, 120),
                    ch.getBudgetMin(), ch.getBudgetMax());
            List<OutlineService.SceneSpec> specs =
                    NovelService.beatsToScenes(rewritten.path("beats").toString(), ch.getBudgetMin());
            if (!specs.isEmpty()) {
                outlineService.materializeScenes(ch.getId(), specs, false);
                beats += specs.size();
            } else {
                log.warn("第 {} 章换皮后没有可用节拍，章纲留空（该章退回 AI 章纲）", no);
            }
            done++;
        }
        log.info("剧情换皮完成：novelId={} 换皮 {} 章 / {} 个场景", novelId, done, beats);
        return new ReskinResult(done, beats);
    }

    /** ① 定新外衣：题材/世界/主角/命名风格 + 新全书大纲。传入样本摘要供「别撞车」比对。 */
    private String generateSkin(long novelId, String sampleTitle, List<String> tags,
                                List<SamplePlotNodeDO> chapters, String seed) {
        StringBuilder outlineCorpus = new StringBuilder();
        for (SamplePlotNodeDO n : chapters) {
            if (n.getLevel().equals("book") && n.getSummary() != null) {
                outlineCorpus.append(truncate(n.getSummary(), 1200));
                break;
            }
        }
        if (outlineCorpus.length() == 0) {
            for (SamplePlotNodeDO n : chapters) {
                outlineCorpus.append(truncate(n.getSummary(), 200)).append(' ');
            }
        }
        String corpus = truncate(outlineCorpus.toString(), 1600);
        String tagLine = tags == null || tags.isEmpty() ? "" : "参考样本的类型标签（**不要照抄**）：" + String.join("、", tags);
        String user = promptTemplates.format(LlmNode.DERIVE_RESKIN, "skin", seed,
                "参考样本：《" + sampleTitle + "》",
                tagLine + "\n参考样本的大纲梗概（只用来避开，不要沿用其中的任何专有名词）：\n" + corpus);
        return llmJson.ask(new LlmPort.ChatRequest(LlmNode.DERIVE_RESKIN, novelId, null,
                        List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.DERIVE_RESKIN, "system")),
                                LlmPort.Message.user(user)),
                        LlmTemps.DERIVE_RESKIN),
                node -> {
                    if (node.path("outline").asText("").isBlank()) {
                        throw new LlmJson.Bad("outline 为空");
                    }
                    return node;
                }, 2).toString();
    }

    /** ② 单章换皮：保节拍数与顺序，只换外衣。 */
    private JsonNode rewriteChapter(long novelId, int chapterNo, String skin, String summary, String beats) {
        String chapterJson = "{\"summary\":" + quote(summary) + ",\"beats\":" + (beats == null || beats.isBlank() ? "[]" : beats) + "}";
        String user = promptTemplates.format(LlmNode.DERIVE_RESKIN, "chapter", skin, chapterNo,
                SAMPLE_TIME_HINT, truncate(summary, 900), truncate(chapterJson, 2400));
        return llmJson.ask(new LlmPort.ChatRequest(LlmNode.DERIVE_RESKIN, novelId, null,
                        List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.DERIVE_RESKIN, "system")),
                                LlmPort.Message.user(user)),
                        LlmTemps.DERIVE_RESKIN),
                node -> {
                    if (node.path("summary").asText("").isBlank()) {
                        throw new LlmJson.Bad("summary 为空");
                    }
                    if (!node.path("beats").isArray()) {
                        throw new LlmJson.Bad("beats 不是数组");
                    }
                    return node;
                }, 2);
    }

    /** 把换皮设定 characters 落成素材卡（人物，★3，不常驻）；返回落库张数。坏结构按 0 处理，不拦换皮。 */
    private int bindCharacters(long novelId, String skin) {
        int n = 0;
        try {
            JsonNode arr = mapper.readTree(skin).path("characters");
            if (!arr.isArray()) {
                return 0;
            }
            for (JsonNode c : arr) {
                String name = c.path("name").asText("").strip();
                if (name.isBlank()) {
                    continue;
                }
                cardData.insert(novelId, "character", name, List.of(),
                        c.path("note").asText("").strip(), null, false, "active", 1);
                n++;
            }
        } catch (Exception e) {
            log.warn("换皮人名表落卡失败（不拦换皮）：{}", e.getMessage());
        }
        return n;
    }

    /** 主角名回填 derive_config.povCharacter（用户已显式指定则不动）：场景提示词的视角人物要跟换皮设定一致。 */
    private void bindPovCharacter(long novelId, String skin) {
        String name = text(skin, "protagonist");
        if (name.isBlank()) {
            return;
        }
        try {
            String cfg = novelData.findDeriveConfig(novelId);
            ObjectNode node = cfg == null || cfg.isBlank()
                    ? mapper.createObjectNode() : (ObjectNode) mapper.readTree(cfg);
            if (!node.path("povCharacter").asText("").isBlank()) {
                return;
            }
            node.put("povCharacter", name);
            novelData.updateDeriveConfig(novelId, node.toString());
            log.info("换皮主角名已回填：povCharacter={}", name);
        } catch (Exception e) {
            log.warn("换皮主角名回填失败（不拦换皮）：{}", e.getMessage());
        }
    }

    /** 换皮设定/大纲 upsert（一本书一份，重跑覆盖，不留第二行）。 */
    private void upsert(long novelId, String kind, String name, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Long id = canonData.findId(novelId, kind, name);
        if (id != null) {
            canonData.updateContent(id, content);
        } else {
            canonData.insert(novelId, kind, name, content);
        }
    }

    /** 已落库的换皮设定（读侧展示用）；无则 null。 */
    public String skinOf(long novelId) {
        for (CanonDocDO d : canonData.listAliveByKindName(SKIN_KIND, SKIN_NAME)) {
            if (d.getNovelId() == novelId) {
                return d.getContent();
            }
        }
        return null;
    }

    private static String text(String json, String key) {
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String quote(String s) {
        try {
            return mapper.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}
