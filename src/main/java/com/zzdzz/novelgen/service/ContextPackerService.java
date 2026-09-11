package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.dao.CanonDocDAO;
import com.zzdzz.novelgen.dao.ChapterDAO;
import com.zzdzz.novelgen.dao.DigestDAO;
import com.zzdzz.novelgen.dao.ForeshadowDAO;
import com.zzdzz.novelgen.dao.StylePackDAO;
import com.zzdzz.novelgen.dao.WorldStateDAO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import java.util.ArrayList;
import java.util.List;

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

    public ContextPackerService(StylePackDAO stylePackDAO,
                                CanonDocDAO canonDocDAO,
                                DigestDAO digestDAO,
                                ForeshadowDAO foreshadowDAO,
                                ChapterDAO chapterDAO,
                                WorldStateDAO worldStateDAO) {
        this.stylePackDAO = stylePackDAO;
        this.canonDocDAO = canonDocDAO;
        this.digestDAO = digestDAO;
        this.foreshadowDAO = foreshadowDAO;
        this.chapterDAO = chapterDAO;
        this.worldStateDAO = worldStateDAO;
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

    public String characters(long novelId) {
        return canonDocDAO.findFirstByKind(novelId, "character");
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

    public Pack packScene(long novelId, int chapterNo, ChapterDO ch, OutlineService.SceneSpec spec,
                          List<String> digests, String prevTail, List<String> foreshadows,
                          String prevSceneText) {
        List<String> ctx = new ArrayList<>();
        for (int i = Math.max(0, digests.size() - 3); i < digests.size(); i++) ctx.add(digests.get(i));

        // 风格包自带量化红线（新风格包）时不再叠加手搓红线，避免两套阈值打架
        String rules = styleRules(novelId);
        String system = rules.contains("【量化风格红线】") ? rules : rules + STYLE_REDLINES;
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

                【人物卡】
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
                world(novelId), characters(novelId),
                foreshadows.isEmpty() ? "（本章无）" : String.join("\n", foreshadows),
                ctx.isEmpty() ? "（本章是第一章，无前情）" : String.join("\n---\n", ctx),
                worldState(novelId, chapterNo) == null ? "（无记录）" : worldState(novelId, chapterNo),
                prevSceneText == null ? (prevTail == null ? "（无）" : prevTail) : prevSceneText);
        return new Pack(system, user);
    }
}
