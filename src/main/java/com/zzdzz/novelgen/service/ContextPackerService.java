package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.model.enums.ForeshadowStatus;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
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
 */
@Service
@RequiredArgsConstructor
public class ContextPackerService {

    private static final String STYLE_REDLINES = """

            【量化风格画像（按此密度写，门禁按同口径验收）】
            - 对话密：每千字约 19 行「」对话——推动情节靠人物说话，不靠叙述转述。
            - 一行一拍：平均每行 15-22 字。
            - 破折号——每千字 2-4 个（同位语补充设定）；省略号……每千字 4-6 个（拖长的思绪）。
            - 对话行句末 85% 以上不加标点（问句可留？）。例：写「走吧」，不要写「走吧。」；旁白行才用句号。
            - 阿拉伯数字只用于钱（"时薪18""31块"），每千字不超过 12 个；
              时间写中文（凌晨两点，不写凌晨2点）；守则条文序号用中文（第一条，不写第1条）。
            - 顿号每千字不超过 1 个；感叹号每千字不超过 2 个。
            """;

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
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;


    public record Pack(String system, String user) {}

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
    }

    /**
     * 上一章事件后果简报（承接注入）：上一章目标 + 章末钩子 + 实际收束。
     * 实际收束优先取 digest 摘要（含信息揭示/情绪落点/钩子兑现），未出摘要（如上一章停在待审批）回退原文末段。
     * 首章或上一章缺失返回 null——章纲、场景上下文、读者评审三处共用。
     */
    public String prevChapterBrief(long novelId, int chapterNo) {
        if (chapterNo <= 1) return null;
        ChapterDO prev = chapterData.find(novelId, chapterNo - 1).orElse(null);
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
        List<ChapterDO> plans = chapterData.listSummariesByNovel(novelId);
        if (!plans.isEmpty()) {
            sb.append("【已有卷纲（往卷已写与当前规划；不得重复其桥段，须衔接其走向）】\n");
            for (ChapterDO c : plans) {
                sb.append("第").append(c.getChapterNo()).append("章《").append(Objects.toString(c.getTitle(), ""))
                        .append("》目标：").append(Objects.toString(c.getGoal(), ""))
                        .append(" 钩子：").append(Objects.toString(c.getHook(), ""))
                        .append(" 时间：").append(Objects.toString(c.getTimeNote(), "紧接"))
                        .append("（状态 ").append(c.getStatus()).append("）\n");
            }
            sb.append('\n');
        }
        List<String> digests = recentDigests(novelId, fromNo, 8);
        if (!digests.isEmpty()) {
            sb.append("【事实账（最近硬事实）】\n").append(String.join("\n---\n", digests)).append("\n\n");
        }
        String ws = worldState(novelId, fromNo);
        if (ws != null) {
            sb.append("【世界状态（截至第 ").append(fromNo - 1).append(" 章结束，必须遵守——物品归属与位置不得凭空变化）】\n")
                    .append(ws).append("\n\n");
        }
        List<ForeshadowDO> fss = foreshadowData.listByNovel(novelId).stream()
                .filter(f -> !ForeshadowStatus.RECOVERED.is(f.getStatus()) && !ForeshadowStatus.DROPPED.is(f.getStatus()))
                .filter(f -> f.getRecoveredIn() == null || f.getRecoveredIn() >= fromNo)
                .toList();
        if (!fss.isEmpty()) {
            sb.append("【伏笔账本（未回收项；proposed=自动提议待排期，被引用即采纳；planted=已埋待回收，被引用即安排回收）】\n");
            for (ForeshadowDO f : fss) {
                sb.append(f.getCode()).append("（").append(f.getStatus());
                if (f.getPlantedIn() != null) sb.append("，埋于第").append(f.getPlantedIn()).append("章");
                if (f.getProposedIn() != null) sb.append("，提议于第").append(f.getProposedIn()).append("章");
                sb.append("）").append(f.getContent()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 卷纲规划完整上下文：世界观+全书大纲、设定卡全量、全局账本、本卷种子大纲（可空）。 */
    public String packVolumePlan(long novelId, int volNo, int fromNo, String seedOutline) {
        StringBuilder sb = new StringBuilder();
        sb.append("【世界观与全书大纲（必须遵守，不得发明矛盾设定）】\n").append(world(novelId)).append("\n\n");
        sb.append(characters(novelId)).append("\n\n");
        sb.append(packLedgers(novelId, fromNo));
        sb.append(retroSection(novelId, volNo));
        sb.append("【本卷种子大纲（最高优先级，须全部落实）】\n")
                .append(seedOutline == null || seedOutline.isBlank()
                        ? "（无——请基于上方全局账本自主设计本卷主线，并在 brief 中说明关键决策）"
                        : seedOutline);
        return sb.toString();
    }

    /** 上卷复盘要点（悬置伏笔/漂移/下卷建议）注入卷纲规划上下文；无上卷报告返回空串。 */
    public String retroSection(long novelId, int volNo) {
        if (volNo <= 1) {
            return "";
        }
        String reportJson = volumeReviewData.findJson(novelId, volNo - 1);
        if (reportJson == null || reportJson.isBlank()) {
            return "";
        }
        String compact = compactRetroReport(reportJson, mapper);
        return compact.isEmpty() ? "" : "【上卷复盘要点（第 " + (volNo - 1)
                + " 卷复盘结论，本卷规划必须做出回应：点名的悬置伏笔优先安排兑现或给出理由）】\n" + compact + "\n\n";
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

    /** 真实章节开篇范例（1-21 章人类手稿前 3 行隔章抽 10 例）：注入本章第一场景做审美对齐。 */
    public String openingExamples(long novelId) {
        List<ChapterDataService.Opening> all = chapterData.findOpeningLines(novelId, 21);
        if (all.isEmpty()) {
            return null;
        }
        int step = Math.max(1, all.size() / 10);
        StringBuilder sb = new StringBuilder("【人类作者开篇范例（本作真实章节的前三行——学它的切入方式与信息密度，禁止照抄其内容与意象）】\n");
        for (int i = 0; i < all.size() && sb.length() < 4000; i += step) {
            sb.append("第").append(all.get(i).chapterNo()).append("章：\n")
                    .append(all.get(i).firstLines()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /** 对白推进范例：本作真实章节中对白最密集的连续 10 行（情节靠人物说话的活样本）。 */
    public String dialogueExcerpt(long novelId) {
        ChapterDataService.Opening ex = chapterData.findDialogueExcerpt(novelId, 21, 10);
        if (ex == null) {
            return null;
        }
        return "【对白推进范例（本作真实章节——情节靠人物说话，学这个节奏与密度，禁止照抄内容）】\n第"
                + ex.chapterNo() + "章：\n" + ex.firstLines();
    }

    /** 本章第一场景的开篇约束块：承接红线（接上章但不复述）+ 张力切入 + 手稿范例。非首场景返回空串。 */
    public String openingSection(long novelId, int sceneNo) {
        if (sceneNo != 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""
                【本章开篇红线（本章第一个场景，逐条硬性执行）】
                - 必须紧接上一章结尾的情境：同一时间、同一地点、同一组在场人物；读者读完前 3 行就能定位「这章接在哪之后」。
                - 上一章结尾留下的钩子必须在场：开篇就是对它的回应、后果或直接推进，不是另起炉灶。
                - 但禁止复述上一章结尾的任何句子，也不要原地停留——第一段就要让情节往前走一步。
                - 从动作、对白、威胁或反常细节切入，禁止环境/天气/氛围铺陈开篇；前 3 行内必须抛出新信息或新威胁。
                - 禁止情绪直给（如「他很紧张」），情绪用动作与细节承载。
                """);
        String samples = openingExamples(novelId);
        if (samples != null) {
            sb.append('\n').append(samples);
        }
        return sb.toString().strip();
    }

    public Pack packScene(long novelId, int chapterNo, ChapterDO ch, OutlineService.SceneSpec spec,
                          List<String> digests, String prevTail, List<String> foreshadows,
                          String prevSceneText) {
        List<String> ctx = new ArrayList<>();
        for (int i = Math.max(0, digests.size() - 3); i < digests.size(); i++) ctx.add(digests.get(i));

        // 风格包自带量化红线（新风格包）时不再叠加手搓红线，避免两套阈值打架
        String rules = styleRules(novelId);
        String system = rules.contains("【量化风格红线】") ? rules : rules + STYLE_REDLINES;
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
        String user = promptTemplates.format(LlmNode.SCENE_DRAFT, "user", """
                任务：写第 %d 章场景 %d。
                本章目标：%s
                本场景目标：%s
                出场人物：%s
                必须让读者知道：%s
                禁止出现：%s
                本场景字数预算：约 %d 字（±15%%），只输出正文。

                【信息密度红线（逐条硬性执行，与开篇红线同等效力）】
                - 情节推进靠人物说话：本章大部分节拍用对白承载，叙述只做对白之间的呼吸——大段描写是本书第一大忌。
                - 每一行必须干一件活：推进事件、揭示新信息、或改变威胁与关系。写完自问「删掉这行读者会少知道什么」，答不出来就删。
                - 一个微动作（点头/起身/放杯/搁笔）最多一行；禁止连续两行写同一对象；禁止给动作写人物志。
                - 比喻（像/仿佛/如同）每千字不超过 %s 个；「不是……是……」式修辞每场景最多 2 次。
                - 观察只许作为行动的前奏——每个观察必须引出下一个动作或决定。

                %s

                【世界观（必须遵守）】
                %s

                %s

                【伏笔任务】
                %s

                【前情摘要】
                %s

                【相关前史（语义检索召回，带出处；与本章情节相关才用，禁止硬凑）】
                %s

                【世界状态（上一章结束时，必须遵守——物品归属与位置不得凭空变化）】
                %s

                【上一章事件后果（本章开场必须与之对接：兑现、交代或明确推进，禁止无视另起炉灶）】
                %s

                【上一场景已写内容（紧接其后继续写；禁止复述其中任何句子——你的第一行必须是全新的句子；禁止重复情节与时间点）】
                %s
                """, chapterNo, spec.sceneNo(), ch.getTitle(), spec.goal(),
                spec.present(), spec.mustReveal(), spec.mustNot(), spec.words(),
                simileRedline,
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
