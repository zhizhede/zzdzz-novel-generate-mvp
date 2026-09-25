package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.model.enums.ForeshadowStatus;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.model.dto.ChapterDTO;
import com.zzdzz.novelgen.model.dto.ForeshadowDTO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.service.data.VolumeReviewDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 上下文打包器：从 repository 装配单场景生成的提示包。
 * 量化风格画像与 style-metrics.json 基线同口径——生成时约束，门禁按同口径验收。
 * 本类零提示词文本：所有固定文案走 PromptTemplateService（node+phase 定位，库值优先、目录回退）。
 */
@Service
@RequiredArgsConstructor
public class ContextPackerService {

    private final StylePackDataService stylePackData;
    private final CanonDocDataService canonData;
    private final DigestDataService digestData;
    private final ForeshadowDataService foreshadowData;
    private final ChapterDataService chapterData;
    private final WorldStateDataService worldStateData;
    private final MaterialCardService cardService;
    private final TuningService tuning;
    private final EmbeddingService embeddingService;
    private final PromptTemplateService promptTemplates;
    private final VolumeReviewDataService volumeReviewData;
    private final com.zzdzz.novelgen.service.data.NovelDataService novelData;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;


    public record Pack(String system, String user) {}

    /** 衍生配置段：POV/主视角 + 掺水量密度口径 + 类型标签。
     * 卷规划（packVolumePlan）与场景生成（packScene）共用；无配置返回空串（fail-open，旧书零变化）。 */
    private String deriveSection(long novelId) {
        return deriveSection(novelId, false);
    }

    /** sceneOnly=true 时只出场景侧要用的段（视角/密度）；标签/红线段由 volume 专用拼装避免重复注入。
     * 段文案从 PromptCatalog（node+sectionKey，{key} 占位）读取——落库可编辑，代码只组装参数。 */
    private String deriveSection(long novelId, boolean sceneOnly) {
        DeriveSupport.Cfg cfg = DeriveSupport.parse(novelData.findDeriveConfig(novelId));
        StringBuilder sb = new StringBuilder();
        if (cfg.pov() != null) {
            sb.append(promptTemplates.getSection(LlmNode.SCENE_DRAFT, "derive_pov",
                    java.util.Map.of("pov", cfg.pov(),
                            "povCharacter", java.util.Objects.requireNonNullElse(cfg.povCharacter(), "（未指定）"))));
        }
        String density = DeriveSupport.densityHint(cfg.water());
        if (density != null) {
            sb.append(promptTemplates.getSection(LlmNode.SCENE_DRAFT, "derive_density",
                    java.util.Map.of("density", density)));
        }
        if (!sceneOnly && cfg.tags() != null && !cfg.tags().isEmpty()) {
            sb.append(promptTemplates.getSection(LlmNode.SCENE_DRAFT, "derive_tags",
                    java.util.Map.of("tags", String.join("、", cfg.tags()))));
        }
        // 衍生差异红线（书 10 实证：克隆的原书主角卡 pinned 注入后，卷规划复述了原书剧情）
        if (!sceneOnly && cfg.sourceSampleId() != null) {
            sb.append(promptTemplates.getSection(LlmNode.SCENE_DRAFT, "derive_redline", java.util.Map.of()));
        }
        return sb.toString();
    }

    /** 卷规划上下文尾部追加：衍生配置段（含类型标签）。由 packVolumePlan 调用。 */
    public String deriveVolumeSection(long novelId) {
        return deriveSection(novelId, false);
    }

    public String styleRules(long novelId) {
        return stylePackData.findRulesMdByNovel(novelId);
    }

    /** 世界观设定 + 全书大纲（misc/大纲 文档存在时自动拼接，进入每章生成上下文）。 */
    public String world(long novelId) {
        String world = canonData.findFirstByKind(novelId, "world");
        String storyOutline = canonData.findContentByKindName(novelId, "misc", "大纲");
        if (storyOutline == null || storyOutline.isBlank()) {
            return world;
        }
        return world + "\n\n【全书大纲】\n" + storyOutline;
    }

    /**
     * 设定卡全量块（规划/审校/章纲用）：作品建有素材卡时按卡注入（pinned 全文+其余摘要），
     * 否则回退 canon 人物卡整文档。返回块自带标题，永不为 null。
     */
    public String characters(long novelId) {
        String block = cardService.fullBlock(novelId);
        if (block != null) {
            return block;
        }
        String doc = canonData.findFirstByKind(novelId, "character");
        return doc == null ? "（无）" : "【人物卡】\n" + doc;
    }

    /**
     * 设定卡场景块：pinned + 按本场景相关文本（章纲/上场景/事实账）别名匹配命中的卡。
     * 无命中时回退全量块——省上下文以不丢人物信息为前提。
     */
    public String charactersForScene(long novelId, String matchText) {
        String block = cardService.sceneBlock(novelId, matchText);
        if (block != null) {
            return block;
        }
        return characters(novelId);
    }

    public List<String> recentDigests(long novelId, int beforeChapter, int n) {
        return digestData.findRecent(novelId, beforeChapter, n);
    }

    public String prevTail(long novelId, int beforeChapter) {
        String fullText = chapterData.findFullText(novelId, beforeChapter - 1);
        if (fullText == null) return null;
        String[] lines = fullText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - 12); i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    public List<String> foreshadowDirectives(long novelId, int chapterNo) {
        return foreshadowData.findDirectives(novelId, chapterNo);
    }    /**
     * 上一章事件后果简报（承接注入）：上一章目标 + 章末钩子 + 实际收束。
     * 实际收束优先取 digest 摘要（含信息揭示/情绪落点/钩子兑现），未出摘要（如上一章停在待审批）回退原文末段。
     * 首章或上一章缺失返回 null——章纲、场景上下文、读者评审三处共用。
     */
    public String prevChapterBrief(long novelId, int chapterNo) {
        if (chapterNo <= 1) return null;
        ChapterDTO prev = chapterData.find(novelId, chapterNo - 1).orElse(null);
        if (prev == null) return null;
        List<String> summaries = digestData.findRecent(novelId, chapterNo - 1, 1);
        String outcome = summaries.isEmpty() ? prevTail(novelId, chapterNo)
                : summaries.get(summaries.size() - 1);
        return "上一章目标：" + Objects.toString(prev.getGoal(), "（无）")
                + "\n上一章章末钩子：" + Objects.toString(prev.getHook(), "（无）")
                + "\n上一章实际收束：" + (outcome == null || outcome.isBlank() ? "（无）" : outcome.strip());
    }

    /** 世界状态快照（上一章结束时）格式化为紧凑清单；无则 null。写错时素材库可人工纠偏。 */
    public String worldState(long novelId, int chapterNo) {
        String json = worldStateData.findLatestBefore(novelId, chapterNo);
        if (json == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            StringBuilder sb = new StringBuilder();
            if (n.hasNonNull("time") && !n.get("time").asText().isBlank()) {
                sb.append("时间：").append(n.get("time").asText()).append('\n');
            }
            appendEntries(sb, "位置", n.get("locations"));
            appendEntries(sb, "随身物品", n.get("possessions"));
            appendEntries(sb, "新承诺", n.get("new_promises"));
            appendEntries(sb, "未解", n.get("unresolved"));
            return sb.isEmpty() ? null : sb.toString().strip();
        } catch (Exception e) {
            return null;
        }
    }

    private void appendEntries(StringBuilder sb, String label, com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isContainerNode() || node.isEmpty()) return;
        sb.append(label).append('：');
        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) sb.append(item.asText()).append('；');
        } else {
            node.fields().forEachRemaining(e -> sb.append(e.getKey()).append('=')
                    .append(e.getValue().asText()).append('；'));
        }
        sb.setLength(sb.length() - 1);
        sb.append('\n');
    }

    // ===== 卷纲规划上下文 =====

    /**
     * 全局账本段（规划与单章重规划共用）：已有卷纲逐行、事实账、世界状态、未回收伏笔账本。
     * fromNo 之前的历史按此口径截取。
     */
    public String packLedgers(long novelId, int fromNo) {
        StringBuilder sb = new StringBuilder();
        List<ChapterDTO> plans = chapterData.listSummariesByNovel(novelId);
        if (!plans.isEmpty()) {
            StringBuilder rows = new StringBuilder();
            for (ChapterDTO c : plans) {
                rows.append("第").append(c.getChapterNo()).append("章《").append(Objects.toString(c.getTitle(), ""))
                        .append("》目标：").append(Objects.toString(c.getGoal(), ""))
                        .append(" 钩子：").append(Objects.toString(c.getHook(), ""))
                        .append(" 时间：").append(Objects.toString(c.getTimeNote(), "紧接"))
                        .append("（状态 ").append(c.getStatus()).append("）\n");
            }
            sb.append(promptTemplates.getSection("common", "ledger_plans",
                    java.util.Map.of("rows", rows.toString())));
        }
        List<String> digests = recentDigests(novelId, fromNo, 8);
        if (!digests.isEmpty()) {
            String joined = String.join("\n---\n", digests);
            sb.append(promptTemplates.getSection("common", "ledger_digests",
                    java.util.Map.of("rows", joined)));
        }
        String ws = worldState(novelId, fromNo);
        if (ws != null) {
            sb.append(promptTemplates.getSection("common", "ledger_worldstate",
                    java.util.Map.of("until_no", String.valueOf(fromNo - 1), "rows", ws)));
        }
        List<ForeshadowDTO> fss = foreshadowData.listByNovel(novelId).stream()
                .filter(f -> !ForeshadowStatus.RECOVERED.is(f.getStatus()) && !ForeshadowStatus.DROPPED.is(f.getStatus()))
                .filter(f -> f.getRecoveredIn() == null || f.getRecoveredIn() >= fromNo)
                .toList();
        if (!fss.isEmpty()) {
            StringBuilder rows = new StringBuilder();
            for (ForeshadowDTO f : fss) {
                rows.append(f.getCode()).append("（").append(f.getStatus());
                if (f.getPlantedIn() != null) rows.append("，埋于第").append(f.getPlantedIn()).append("章");
                if (f.getProposedIn() != null) rows.append("，提议于第").append(f.getProposedIn()).append("章");
                rows.append("）").append(f.getContent()).append('\n');
            }
            sb.append(promptTemplates.getSection("common", "ledger_foreshadows",
                    java.util.Map.of("rows", rows.toString())));
        }
        return sb.toString();
    }

    /** 卷纲规划完整上下文：世界观+全书大纲、设定卡全量、全局账本、上卷复盘、衍生配置、本卷种子大纲（可空）。框架段均落库（common 命名空间）。 */
    public String packVolumePlan(long novelId, int volNo, int fromNo, String seedOutline) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.getSection("common", "volume_world",
                java.util.Map.of("world", java.util.Objects.toString(world(novelId), ""))));
        sb.append(characters(novelId)).append("\n\n");
        sb.append(packLedgers(novelId, fromNo));
        sb.append(retroSection(novelId, volNo));
        sb.append(deriveVolumeSection(novelId));
        String seed = seedOutline == null || seedOutline.isBlank()
                ? promptTemplates.getSection("common", "volume_seed_empty", java.util.Map.of())
                : seedOutline;
        sb.append(promptTemplates.getSection("common", "volume_seed",
                java.util.Map.of("seed", seed)));
        return sb.toString();
    }

    /** 上卷复盘要点（悬置伏笔/漂移/下卷建议）注入卷纲规划上下文；无上卷报告返回空串。框架文案走 retro_section 段（落库）。 */
    public String retroSection(long novelId, int volNo) {
        if (volNo <= 1) {
            return "";
        }
        String reportJson = volumeReviewData.findJson(novelId, volNo - 1);
        if (reportJson == null || reportJson.isBlank()) {
            return "";
        }
        String compact = compactRetroReport(reportJson, mapper);
        if (compact.isEmpty()) {
            return "";
        }
        return promptTemplates.getSection("common", "retro_section",
                java.util.Map.of("vol_no", String.valueOf(volNo - 1), "compact", compact));
    }

    /** 复盘报告 → 紧凑文本：总评一行 + drifts（最多 4 条）+ 下卷建议，总长截到 1200 字内。 */
    public static String compactRetroReport(String reportJson, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        StringBuilder sb = new StringBuilder();
        try {
            var root = mapper.readTree(reportJson);
            var review = root.has("review") ? root.path("review") : root;
            String overall = review.path("overall").asText("");
            String summary = review.path("summary").asText("");
            if (!summary.isBlank()) {
                sb.append("- 总评（").append(overall).append("）：").append(clip(summary, 120)).append('\n');
            }
            int n = 0;
            for (var d : review.path("drifts")) {
                if (n >= 4) break;
                String line = "- [" + d.path("severity").asText("") + "|" + d.path("type").asText("") + "] "
                        + d.path("where").asText("") + "：" + clip(d.path("issue").asText(""), 80);
                String sug = d.path("suggestion").asText("");
                if (!sug.isBlank()) line += " → " + clip(sug, 60);
                sb.append(line).append('\n');
                n++;
            }
            String next = review.path("next_volume").asText("");
            if (!next.isBlank()) {
                if (next.length() > 140) next = next.substring(0, 140) + "…";
                sb.append("- 下卷建议：").append(next).append('\n');
            }
        } catch (Exception e) {
            return "";
        }
        String out = sb.toString();
        return out.length() > 1200 ? out.substring(0, 1200) : out;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 真实章节开篇范例（1-21 章人类手稿前 3 行隔章抽 10 例）：注入本章第一场景做审美对齐。框架文案走 craft_opening 段（落库）。 */
    public String openingExamples(long novelId) {
        List<ChapterDataService.Opening> all = chapterData.findOpeningLines(novelId, 21);
        if (all.isEmpty()) {
            return null;
        }
        int step = Math.max(1, all.size() / 10);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < all.size() && body.length() < 4000; i += step) {
            body.append("第").append(all.get(i).chapterNo()).append("章：\n")
                    .append(all.get(i).firstLines()).append("\n\n");
        }
        return promptTemplates.getSection(LlmNode.SCENE_DRAFT, "craft_opening",
                java.util.Map.of("examples", body.toString().strip()));
    }

    /** 对白推进范例：本作真实章节中对白最密集的连续 10 行（情节靠人物说话的活样本）。框架文案走 craft_dialogue 段（落库）。 */
    public String dialogueExcerpt(long novelId) {
        ChapterDataService.Opening ex = chapterData.findDialogueExcerpt(novelId, 21, 10);
        if (ex == null) {
            return null;
        }
        return promptTemplates.getSection(LlmNode.SCENE_DRAFT, "craft_dialogue",
                java.util.Map.of("chapter_no", String.valueOf(ex.chapterNo()),
                        "excerpt", ex.firstLines() == null ? "" : ex.firstLines()));
    }

    /** 本章第一场景的开篇约束块：承接红线（接上章但不复述）+ 张力切入 + 手稿范例。非首场景返回空串。红线文案走 opening_redlines 段（落库）。 */
    public String openingSection(long novelId, int sceneNo) {
        if (sceneNo != 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder(promptTemplates.getSection(LlmNode.SCENE_DRAFT, "opening_redlines",
                java.util.Map.of()));
        String samples = openingExamples(novelId);
        if (samples != null) {
            sb.append('\n').append(samples);
        }
        return sb.toString().strip();
    }

    public Pack packScene(long novelId, int chapterNo, ChapterDTO ch, OutlineService.SceneSpec spec,
                          List<String> digests, String prevTail, List<String> foreshadows,
                          String prevSceneText) {
        List<String> ctx = new ArrayList<>();
        for (int i = Math.max(0, digests.size() - 3); i < digests.size(); i++) ctx.add(digests.get(i));

        // 风格包自带量化红线（新风格包）时不再叠加手搓红线，避免两套阈值打架
        String rules = styleRules(novelId);
        String system = rules.contains("【量化风格红线】") ? rules : rules + promptTemplates.get(LlmNode.SCENE_DRAFT, "style_redlines");
        // 写作工艺块：首场景=开篇红线+手稿开篇范例；所有场景=对白推进范例
        String craft = openingSection(novelId, spec.sceneNo());
        String dex = dialogueExcerpt(novelId);
        if (dex != null) {
            craft = (craft.isEmpty() ? "" : craft + "\n\n") + dex;
        }
        // 设定卡匹配文本：本章目标/钩子 + 场景目标 + 前文 + 事实账近况 + 上一章后果（命中才注入对应卡，省上下文）
        String prevBrief = prevChapterBrief(novelId, chapterNo);
        String matchText = String.join("\n",
                String.valueOf(ch.getTitle()), String.valueOf(ch.getGoal()), String.valueOf(ch.getHook()),
                spec.goal(), prevSceneText == null ? String.valueOf(prevTail) : prevSceneText,
                prevBrief == null ? "" : prevBrief,
                String.join("\n", ctx));
        // 提示词中的比喻密度红线走 tuning（与门禁 simile 上限是两道闸：一个管写、一个管验收）
        String simileRedline = java.math.BigDecimal
                .valueOf(tuning.d("prompt_simile_per1k", 3.0)).stripTrailingZeros().toPlainString();
        // RAG 语义召回：本章目标+钩子+场景目标 作查询，近三章事实账已在摘要里故不重复召回
        String ragSection = embeddingService.searchSection(novelId, chapterNo,
                Objects.toString(ch.getGoal(), "") + "\n" + Objects.toString(ch.getHook(), "") + "\n" + spec.goal(),
                chapterNo - 3);
        String user = promptTemplates.format(LlmNode.SCENE_DRAFT, "user", chapterNo, spec.sceneNo(), ch.getTitle(), spec.goal(),
                spec.present(), spec.mustReveal(), spec.mustNot(), spec.words(),
                simileRedline,
                deriveSection(novelId),
                craft,
                world(novelId),
                charactersForScene(novelId, matchText),
                foreshadows.isEmpty() ? "（本章无）" : String.join("\n", foreshadows),
                ctx.isEmpty() ? "（本章是第一章，无前情）" : String.join("\n---\n", ctx),
                ragSection == null ? "（无相关命中）" : ragSection,
                worldState(novelId, chapterNo) == null ? "（无记录）" : worldState(novelId, chapterNo),
                prevBrief == null || prevBrief.isBlank() ? "（本章是第一章，无上一章后果）" : prevBrief,
                prevSceneText == null ? (prevTail == null ? "（无）" : prevTail) : prevSceneText);
        return new Pack(system, user);
    }
}
