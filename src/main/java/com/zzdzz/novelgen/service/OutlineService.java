package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** AI 章纲生成：卷纲行 + 世界观 + 前情 → 场景列表（严格 JSON，失败原因喂回重试）。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutlineService {


    public record SceneSpec(int sceneNo, String goal, List<String> present,
                            List<String> mustReveal, List<String> mustNot, int words) {}

    private final LlmJson llmJson;
    private final ObjectMapper mapper;
    private final ChapterDataService chapterData;
    private final SceneDataService sceneData;
    private final PromptTemplateService promptTemplates;
    private final TuningService tuning;


    public ChapterDO loadChapter(long novelId, int chapterNo) {
        return chapterData.find(novelId, chapterNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章不存在: " + chapterNo));    }

    public void generate(long novelId, ChapterDO ch, String world, String characters,
                         List<String> directives, List<String> digests, String prevTail,
                         String prevBrief) {
        generate(novelId, ch, world, characters, directives, digests, prevTail, prevBrief, false);
    }

    /**
     * preserveChapterState=true（用户显式勾了「含已有正文的章」）：只写章纲（outline_yaml）与场景拆解，
     * **不把章状态退回 OUTLINED、不删门禁报告**——给成品章补规划不该让管线以为它要重写。
     * 老路径 `resetForReoutline` 会 markOutlined（状态→OUTLINED）并删场景/门禁报告，之后一续跑就会把这一章重写掉。
     */
    public void generate(long novelId, ChapterDO ch, String world, String characters,
                         List<String> directives, List<String> digests, String prevTail,
                         String prevBrief, boolean preserveChapterState) {
        // 流 A：未消费的打回意见拼进卷纲目标行（不动提示词模板目录），章纲落库成功后清零；
        // 长度护栏走调参键 reject_reason_max_len（默认 200），超长截断并标注
        String goal = ch.getGoal();
        String rejectReason = ch.getRejectReason();
        if (rejectReason != null && !rejectReason.isBlank()) {
            int maxLen = tuning.i("reject_reason_max_len", TuningDefaults.REJECT_REASON_MAX_LEN);
            String trimmed = rejectReason.strip();
            if (trimmed.length() > maxLen) {
                trimmed = trimmed.substring(0, maxLen) + "…（意见超长已截断）";
            }
            goal = goal + promptTemplates.getSection(LlmNode.OUTLINE, "reject_suffix",
                    java.util.Map.of("reason", trimmed));
        }
        String user = promptTemplates.format(LlmNode.OUTLINE, "user", ch.getChapterNo(), ch.getTitle(), goal, ch.getHook(),
                Objects.toString(ch.getTimeNote(), "紧接上一章，无跳跃"),
                Objects.toString(ch.getRuleRefs(), "[]"), Objects.toString(ch.getForeshadowRefs(), "[]"),
                ch.getBudgetMin(), ch.getBudgetMax(), world, characters,
                digests == null || digests.isEmpty() ? "（本章是第一章，无前情）" : String.join("\n---\n", digests),
                prevBrief == null || prevBrief.isBlank() ? "（本章是第一章，无上一章后果）" : prevBrief,
                prevTail == null ? "（无）" : prevTail);

        JsonNode scenes = askScenes(LlmNode.OUTLINE, novelId, ch.getId(), user, 2);
        if (rejectReason != null && !rejectReason.isBlank()) {
            chapterData.clearRejectReason(ch.getId()); // 意见已注入本次章纲，消费清零
        }
        materialize(ch.getId(), scenes, preserveChapterState);
    }

    /**
     * **从已有正文反推章纲**（解析链的章纲步，2026-10-03 增）：读本章正文，把它实际分成的场景拆出来。
     * 与 {@link #generate} 的区别是输入——那里输入是「卷纲目标/钩子/前情」（写之前的口径），
     * 这里输入是正文本身（事后描述）。落库走保全状态分支：只写章纲与场景，章状态/正文/门禁报告都不动。
     */
    public void deriveFromText(long novelId, int chapterNo) {
        ChapterDO ch = loadChapter(novelId, chapterNo);
        String text = ch.getFullText();
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "第 " + chapterNo + " 章没有正文，无法反推章纲");
        }
        String user = promptTemplates.format(LlmNode.BOOK_CHAPTER_OUTLINE, "user",
                ch.getChapterNo(), Objects.toString(ch.getTitle(), ""), text.length(), text);
        JsonNode scenes = askScenes(LlmNode.BOOK_CHAPTER_OUTLINE, novelId, ch.getId(), user, 2);
        materialize(ch.getId(), scenes, true);
        log.info("章纲反推落库：chapterNo={} scenes={}", chapterNo, scenes.size());
    }

    /** 反推结果（解析链按它写「已反推 n/m 章」）。 */
    public record DeriveResult(int derived, int skipped, int failed, int requested, int bookChapters) {
    }

    /**
     * 全书反推章纲：把本书**有正文**的章按章号升序取前 cap 章逐章反推（单章几十秒，不做无上限全跑）。
     * overwrite=false 时已有章纲的章跳过（人工/上一轮写过的优先）；单章失败只记数不中断（逐步 fail-open 同款口径）。
     */
    public DeriveResult deriveChapterOutlines(long novelId, boolean overwrite, int cap) {
        List<ChapterDataService.ChapterTextRow> texts = chapterData.listTextsByNovel(novelId);
        List<ChapterDataService.ChapterTextRow> picked = outlineCandidates(texts, cap);
        if (picked.isEmpty()) {
            return new DeriveResult(0, 0, 0, 0, texts.size());
        }
        Map<Integer, Long> existing = new HashMap<>();
        for (ChapterDataService.ChapterPlanRow r : chapterData.listPlanRowsByNovel(novelId)) {
            existing.put(r.chapterNo(), r.outlineChars());
        }
        int derived = 0;
        int skipped = 0;
        int failed = 0;
        for (ChapterDataService.ChapterTextRow t : picked) {
            if (!overwrite && existing.getOrDefault(t.chapterNo(), 0L) > 0) {
                skipped++;
                continue;
            }
            try {
                deriveFromText(novelId, t.chapterNo());
                derived++;
            } catch (Exception e) {
                failed++;
                log.warn("章纲反推失败（继续下一章）：novelId={} chapterNo={} {}", novelId, t.chapterNo(), e.toString());
            }
        }
        log.info("章纲反推完成：novelId={} 本书有正文 {} 章，本次 {} 章 → 反推 {}、跳过 {}、失败 {}",
                novelId, texts.size(), picked.size(), derived, skipped, failed);
        return new DeriveResult(derived, skipped, failed, picked.size(), texts.size());
    }

    /** 反推候选：有正文的章按章号升序取前 cap 章（纯函数，便于单测；空正文/非正数 cap 一律空）。 */
    static List<ChapterDataService.ChapterTextRow> outlineCandidates(
            List<ChapterDataService.ChapterTextRow> texts, int cap) {
        if (texts == null || texts.isEmpty() || cap <= 0) {
            return List.of();
        }
        return texts.stream()
                .filter(t -> t.fullText() != null && !t.fullText().isBlank())
                .sorted(Comparator.comparingInt(ChapterDataService.ChapterTextRow::chapterNo))
                .limit(cap)
                .toList();
    }

    /** 场景数组 → 落库：写章纲（保全状态或重置）+ 物化场景行。两条入口（规划/反推）共用这一处写路径。 */
    private void materialize(long chapterId, JsonNode scenes, boolean preserveChapterState) {
        List<String> goals = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> reveal = new ArrayList<>();
        List<String> not = new ArrayList<>();
        List<Integer> words = new ArrayList<>();
        for (JsonNode s : scenes) {
            goals.add(s.path("goal").asText(""));
            present.add(jsonText(s.path("present")));
            reveal.add(jsonText(s.path("must_reveal")));
            not.add(jsonText(s.path("must_not")));
            words.add(s.path("words").asInt(900));
        }
        // 先清旧场景与门禁报告（外键顺序在 repository 内处理），再物化新场景；
        // 已有正文的章走「保全状态」分支：只换章纲，章状态/正文/门禁报告都不动
        if (preserveChapterState) {
            chapterData.updateOutlineYaml(chapterId, scenes.toString());
        } else {
            chapterData.resetForReoutline(chapterId, scenes.toString());
        }
        sceneData.replaceAll(chapterId, goals, present, reveal, not, words);
        log.info("章纲落库: scenes={} 保全状态={}", scenes.size(), preserveChapterState);
    }

    /**
     * 剧情迁移：把**预建的场景拆解**直接物化（不经 LLM），与章纲生成共用同一落库写路径
     * （写 outline_yaml + 物化 chapter_scenes）。管线步骤 1 见场景已存在即跳过 AI 章纲，
     * 于是迁移来的剧情成为该章的唯一方向约束。
     *
     * @param preserveChapterState true=只换章纲，不动章状态/正文/门禁报告（已有正文的章）
     */
    public void materializeScenes(long chapterId, List<SceneSpec> specs, boolean preserveChapterState) {
        var arr = mapper.createArrayNode();
        for (SceneSpec s : specs) {
            var o = arr.addObject();
            o.put("no", s.sceneNo());
            o.put("goal", s.goal() == null ? "" : s.goal());
            o.set("present", mapper.valueToTree(s.present() == null ? List.of() : s.present()));
            o.set("must_reveal", mapper.valueToTree(s.mustReveal() == null ? List.of() : s.mustReveal()));
            o.set("must_not", mapper.valueToTree(s.mustNot() == null ? List.of() : s.mustNot()));
            o.put("words", s.words());
        }
        materialize(chapterId, arr, preserveChapterState);
    }

    public List<SceneSpec> loadSpecs(long chapterId) {
        return sceneData.findByChapter(chapterId).stream()
                .map(s -> new SceneSpec(s.getSceneNo(), s.getGoal() == null ? "" : s.getGoal(),
                        toStringList(s.getPresent()), toStringList(s.getMustReveal()),
                        toStringList(s.getMustNot()), s.getWordsBudget()))
                .toList();
    }

    /** 规划口径的章纲（LlmNode.OUTLINE）。 */
    private JsonNode askScenes(long novelId, long chapterId, String user, int tries) {
        return askScenes(LlmNode.OUTLINE, novelId, chapterId, user, tries);
    }

    /** novelId/chapterId 必传：章纲调用归章（台账按章回放/档案聚合依赖此前修的双空归属缺口）。 */
    private JsonNode askScenes(String node, long novelId, long chapterId, String user, int tries) {
        return llmJson.ask(new LlmPort.ChatRequest(
                        node, novelId, chapterId,
                        List.of(LlmPort.Message.system(promptTemplates.get(node, "system")),
                                LlmPort.Message.user(user)),
                        LlmTemps.OUTLINE),
                this::validateScenes, tries);
    }

    /** 两个入口共用同一套结构校验：2-3 个场景、每场 goal 非空。 */
    private JsonNode validateScenes(JsonNode node) {
        JsonNode arr = node.path("scenes");
        if (!arr.isArray() || arr.size() < 2 || arr.size() > 3) {
            throw new LlmJson.Bad("scenes 必须是 2-3 个元素的数组");
        }
        for (JsonNode s : arr) {
            if (s == null || !s.isObject() || s.path("goal").asText("").isBlank()) {
                throw new LlmJson.Bad("scenes 里存在非对象元素或 goal 为空");
            }
        }
        return arr;
    }

    /** 模型可能漏字段：MissingNode/Null 的 toString 是空串，对 ::jsonb 是非法输入，兜底为 [] */
    private String jsonText(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "[]" : node.toString();
    }

    private List<String> toStringList(String json) {
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode n : mapper.readTree(json)) out.add(n.asText());
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
