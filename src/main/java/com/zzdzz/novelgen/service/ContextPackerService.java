package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import com.zzdzz.novelgen.dao.CanonDocDAO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.DigestDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.StylePackDAO;
import com.zzdzz.novelgen.dao.WorldStateDAO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 上下文打包器：从 repository 装配单场景生成的提示包。
 * 量化风格画像与 style-metrics.json 基线同口径——生成时约束，门禁按同口径验收。
 */
@Service
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

    private final StylePackDAO stylePackDAO;
    private final CanonDocDAO canonDocDAO;
    private final DigestDAO digestDAO;
    private final ForeshadowDAO foreshadowDAO;
    private final ChapterDAO chapterDAO;
    private final WorldStateDAO worldStateDAO;
    private final MaterialCardService cardService;

    public ContextPackerService(StylePackDAO stylePackDAO,
                                CanonDocDAO canonDocDAO,
                                DigestDAO digestDAO,
                                ForeshadowDAO foreshadowDAO,
                                ChapterDAO chapterDAO,
                                WorldStateDAO worldStateDAO,
                                MaterialCardService cardService) {
        this.stylePackDAO = stylePackDAO;
        this.canonDocDAO = canonDocDAO;
        this.digestDAO = digestDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.chapterDAO = chapterDAO;
        this.worldStateDAO = worldStateDAO;
        this.cardService = cardService;
    }

    public record Pack(String system, String user) {}

    public String styleRules(long novelId) {
        return stylePackDAO.findRulesMdByNovel(novelId);
    }

    /** 世界观设定 + 全书大纲（misc/大纲 文档存在时自动拼接，进入每章生成上下文）。 */
    public String world(long novelId) {
        String world = canonDocDAO.findFirstByKind(novelId, "world");
        String storyOutline = canonDocDAO.findContentByKindName(novelId, "misc", "大纲");
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
        String doc = canonDocDAO.findFirstByKind(novelId, "character");
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
        return digestDAO.findRecent(novelId, beforeChapter, n);
    }

    public String prevTail(long novelId, int beforeChapter) {
        String fullText = chapterDAO.findFullText(novelId, beforeChapter - 1);
        if (fullText == null) return null;
        String[] lines = fullText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - 12); i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    public List<String> foreshadowDirectives(long novelId, int chapterNo) {
        return foreshadowDAO.findDirectives(novelId, chapterNo);
    }

    /** 世界状态快照（上一章结束时）格式化为紧凑清单；无则 null。写错时素材库可人工纠偏。 */
    public String worldState(long novelId, int chapterNo) {
        String json = worldStateDAO.findLatestBefore(novelId, chapterNo);
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
        List<ChapterDO> plans = chapterDAO.listSummariesByNovel(novelId);
        if (!plans.isEmpty()) {
            sb.append("【已有卷纲（往卷已写与当前规划；不得重复其桥段，须衔接其走向）】\n");
            for (ChapterDO c : plans) {
                sb.append("第").append(c.chapterNo()).append("章《").append(Objects.toString(c.title(), ""))
                        .append("》目标：").append(Objects.toString(c.goal(), ""))
                        .append(" 钩子：").append(Objects.toString(c.hook(), ""))
                        .append(" 时间：").append(Objects.toString(c.timeNote(), "紧接"))
                        .append("（状态 ").append(c.status()).append("）\n");
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
        List<ForeshadowDO> fss = foreshadowDAO.listByNovel(novelId).stream()
                .filter(f -> !"recovered".equals(f.status()) && !"dropped".equals(f.status()))
                .filter(f -> f.recoveredIn() == null || f.recoveredIn() >= fromNo)
                .toList();
        if (!fss.isEmpty()) {
            sb.append("【伏笔账本（未回收项；proposed=自动提议待排期，被引用即采纳；planted=已埋待回收，被引用即安排回收）】\n");
            for (ForeshadowDO f : fss) {
                sb.append(f.code()).append("（").append(f.status());
                if (f.plantedIn() != null) sb.append("，埋于第").append(f.plantedIn()).append("章");
                if (f.proposedIn() != null) sb.append("，提议于第").append(f.proposedIn()).append("章");
                sb.append("）").append(f.content()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 卷纲规划完整上下文：世界观+全书大纲、设定卡全量、全局账本、本卷种子大纲（可空）。 */
    public String packVolumePlan(long novelId, int fromNo, String seedOutline) {
        StringBuilder sb = new StringBuilder();
        sb.append("【世界观与全书大纲（必须遵守，不得发明矛盾设定）】\n").append(world(novelId)).append("\n\n");
        sb.append(characters(novelId)).append("\n\n");
        sb.append(packLedgers(novelId, fromNo));
        sb.append("【本卷种子大纲（最高优先级，须全部落实）】\n")
                .append(seedOutline == null || seedOutline.isBlank()
                        ? "（无——请基于上方全局账本自主设计本卷主线，并在 brief 中说明关键决策）"
                        : seedOutline);
        return sb.toString();
    }

    public Pack packScene(long novelId, int chapterNo, ChapterDO ch, OutlineService.SceneSpec spec,
                          List<String> digests, String prevTail, List<String> foreshadows,
                          String prevSceneText) {
        List<String> ctx = new ArrayList<>();
        for (int i = Math.max(0, digests.size() - 3); i < digests.size(); i++) ctx.add(digests.get(i));

        // 风格包自带量化红线（新风格包）时不再叠加手搓红线，避免两套阈值打架
        String rules = styleRules(novelId);
        String system = rules.contains("【量化风格红线】") ? rules : rules + STYLE_REDLINES;
        // 设定卡匹配文本：本章目标/钩子 + 场景目标 + 前文 + 事实账近况（命中才注入对应卡，省上下文）
        String matchText = String.join("\n",
                String.valueOf(ch.title()), String.valueOf(ch.goal()), String.valueOf(ch.hook()),
                spec.goal(), prevSceneText == null ? String.valueOf(prevTail) : prevSceneText,
                String.join("\n", ctx));
        String user = """
                任务：写第 %d 章场景 %d。
                本章目标：%s
                本场景目标：%s
                出场人物：%s
                必须让读者知道：%s
                禁止出现：%s
                本场景字数预算：约 %d 字（±15%%），只输出正文。

                【世界观（必须遵守）】
                %s

                %s

                【伏笔任务】
                %s

                【前情摘要】
                %s

                【世界状态（上一章结束时，必须遵守——物品归属与位置不得凭空变化）】
                %s

                【上一场景已写内容（紧接其后继续写，禁止重复其中任何情节与时间点）】
                %s
                """.formatted(chapterNo, spec.sceneNo(), ch.title(), spec.goal(),
                spec.present(), spec.mustReveal(), spec.mustNot(), spec.words(),
                world(novelId),
                charactersForScene(novelId, matchText),
                foreshadows.isEmpty() ? "（本章无）" : String.join("\n", foreshadows),
                ctx.isEmpty() ? "（本章是第一章，无前情）" : String.join("\n---\n", ctx),
                worldState(novelId, chapterNo) == null ? "（无记录）" : worldState(novelId, chapterNo),
                prevSceneText == null ? (prevTail == null ? "（无）" : prevTail) : prevSceneText);
        return new Pack(system, user);
    }
}
