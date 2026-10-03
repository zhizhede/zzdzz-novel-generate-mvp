package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.model.dto.ChapterDTO;
import com.zzdzz.novelgen.model.dto.MaterialCardDTO;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 导入书籍的「资产回填」三步（解析链里最需要 LLM 的三步，落库位置各不相同）：
 * ①素材卡 → material_cards（新节点 {@link LlmNode#BOOK_CARDS}）；
 * ②全书大纲 → canon_docs(misc/大纲)——**复用** {@link LlmNode#SAMPLE_OUTLINE} 提示词；
 * ③世界观文档 → canon_docs(world/世界观)——**复用** {@link LlmNode#SAMPLE_WORLD} 提示词。
 * 复用样本节点提示词是刻意的：提示词只在 PromptCatalog 有一份，双源必然漂移（历史教训）。
 * 三步都写成幂等/可覆盖：素材卡按 (kind,name) 跳过已有（解析链选了「覆盖已有」则改为更新那一行，保留人工 pinned/status）、
 * 大纲与世界观覆盖同名文档。
 * 入库前一律先跑一遍 digest（解析链里 DIGESTS 排在前面），所以这里能拿事实账当摘要源。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookAssetExtractService {

    /** 大纲与世界观落在 canon 的这两个键下（与旧口径一致，别另起名字）。 */
    private static final String STORY_KIND = "misc";
    private static final String STORY_NAME = "大纲";
    private static final String WORLD_KIND = "world";
    private static final String WORLD_NAME = "世界观";

    /** 素材卡单次最多落库张数（提示词里也让模型按此裁剪，这里再兜一层，防模型不听话塞几百张）。 */
    private static final int MAX_CARDS = 40;
    /** 喂给提示词的摘要上限（章摘要与事实账都用它截断，控制单次调用体积）。 */
    private static final int DIGEST_BLOCK_MAX_CHARS = 24000;

    private static final Set<String> KINDS = Set.of(
            MaterialCardDTO.KIND_CHARACTER, MaterialCardDTO.KIND_ITEM, MaterialCardDTO.KIND_LOCATION,
            MaterialCardDTO.KIND_LANDMARK, MaterialCardDTO.KIND_PHENOMENON, MaterialCardDTO.KIND_DISASTER,
            MaterialCardDTO.KIND_ORG, MaterialCardDTO.KIND_MISC);

    private final NovelDataService novelData;
    private final ChapterDataService chapterData;
    private final DigestDataService digestData;
    private final MaterialCardDataService cardData;
    private final CanonDocDataService canonData;
    private final LlmPort llm;
    private final LlmJson llmJson;
    private final PromptTemplateService promptTemplates;

    /** 素材卡写入结果：created+updated ＝本次实际落库张数（跳过模式下 updated 恒为 0）。 */
    public record CardWriteResult(int created, int updated) {
    }

    /** ①素材卡：章节结构 + 事实账 → 设定卡，写 material_cards（已有同名同类卡跳过）。 */
    public CardWriteResult extractCards(long novelId) {
        return extractCards(novelId, false);
    }

    /**
     * overwrite=false（默认）：已有同名同类卡跳过——人工写过的优先，不覆盖不重复。
     * overwrite=true（用户选「覆盖已有」）：用模型新结果更新那一行（摘要/正文/别名/出处），
     * 但**保留**卡上的人工状态（pinned 钉住标记与 status），免得覆盖把人工取舍一起抹掉。
     */
    public CardWriteResult extractCards(long novelId, boolean overwrite) {
        NovelDTO novel = requireNovel(novelId);
        String block = chapterDigestBlock(novelId);
        if (block.isBlank()) {
            log.info("书籍素材卡提取跳过：无章节摘要可用 novelId={}", novelId);
            return new CardWriteResult(0, 0);
        }
        String user = promptTemplates.format(LlmNode.BOOK_CARDS, "user",
                String.valueOf(MAX_CARDS), novel.getTitle(), block);
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.BOOK_CARDS, novelId, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.BOOK_CARDS, "system")),
                        LlmPort.Message.user(user)), LlmTemps.DIGEST);
        List<CardDraft> drafts = llmJson.ask(req, this::parseCards, 2);

        Map<String, MaterialCardDTO> existing = overwrite ? cardsByKindName(novelId) : Map.of();
        int created = 0;
        int updated = 0;
        for (CardDraft d : drafts) {
            if (d.name().isBlank()) {
                continue;
            }
            MaterialCardDTO hit = existing.get(cardKey(d.kind(), d.name()));
            if (hit != null) {
                cardData.update(hit.getId(), d.name(), d.aliases(), d.summary(), d.content(),
                        hit.isPinned(), hit.getStatus(), d.sourceChapter());
                updated++;
                continue;
            }
            if (cardData.exists(novelId, d.kind(), d.name())) {
                continue;   // 已有同名同类卡且本次选择跳过
            }
            cardData.insert(novelId, d.kind(), d.name(), d.aliases(), d.summary(), d.content(),
                    d.pinned(), "active", d.sourceChapter());
            created++;
        }
        log.info("书籍素材卡提取：novelId={} 模型给出 {} 张，新增 {} 张、覆盖 {} 张", novelId, drafts.size(), created, updated);
        return new CardWriteResult(created, updated);
    }

    /** 已有卡按 kind+name 建索引（覆盖模式的命中判断；name 只比精确值，与唯一索引同口径）。 */
    private Map<String, MaterialCardDTO> cardsByKindName(long novelId) {
        Map<String, MaterialCardDTO> out = new HashMap<>();
        for (MaterialCardDTO c : cardData.listByNovel(novelId, null)) {
            out.put(cardKey(c.getKind(), c.getName()), c);
        }
        return out;
    }

    private static String cardKey(String kind, String name) {
        return kind + "\u0000" + name;
    }

    /** ②全书大纲：章节结构 + 事实账 → 大纲文本，写 canon(misc/大纲) 并返回正文。 */
    public String synthesizeOutline(long novelId) {
        NovelDTO novel = requireNovel(novelId);
        String block = chapterDigestBlock(novelId);
        String stats = "书名：" + novel.getTitle() + "\n章节数："
                + chapterData.listTextsByNovel(novelId).size() + "\n";
        String user = promptTemplates.format(LlmNode.SAMPLE_OUTLINE, "user", stats, truncate(block, 30000), "");
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_OUTLINE, novelId, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_OUTLINE, "system")),
                        LlmPort.Message.user(user)), LlmTemps.DIGEST);
        String outline = llmJson.ask(req, node -> {
            if (!node.isObject() || node.isEmpty()) {
                throw new IllegalStateException("大纲合成输出为空");
            }
            return renderOutline(node);
        }, 2);
        canonUpsert(novelId, STORY_KIND, STORY_NAME, outline);
        log.info("书籍大纲合成落库：novelId={} {} 字", novelId, outline.length());
        return outline;
    }

    /** ③世界观文档：章节结构 + 已有素材卡 → 世界观文本，写 canon(world/世界观) 并返回正文。 */
    public String synthesizeWorld(long novelId) {
        NovelDTO novel = requireNovel(novelId);
        String block = chapterDigestBlock(novelId);
        String cardBlock = cardBlock(novelId);
        String user = promptTemplates.format(LlmNode.SAMPLE_WORLD, "user",
                truncate(block, 20000), truncate(cardBlock, 8000));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.SAMPLE_WORLD, novelId, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.SAMPLE_WORLD, "system")),
                        LlmPort.Message.user(user)), LlmTemps.DIGEST);
        LlmPort.ChatResult r = llm.chat(req);
        String world = r.content() == null ? "" : r.content().strip();
        if (world.isBlank()) {
            throw new IllegalStateException("世界观合成输出为空");
        }
        world = stripFence(world);
        canonUpsert(novelId, WORLD_KIND, WORLD_NAME, world);
        log.info("书籍世界观合成落库：novelId={} {} 字", novelId, world.length());
        return world;
    }

    // ===== 输入拼装 =====

    /** 章节结构 + 该章事实账（无事实账时退回章名与目标/钩子），供三步共用。 */
    private String chapterDigestBlock(long novelId) {
        List<ChapterDTO> chapters = chapterData.listSummariesByNovel(novelId);
        Map<Integer, String> digestByChapterNo = new LinkedHashMap<>();
        for (DigestDataService.DigestItem d : digestData.listByNovel(novelId)) {
            if (d.contentMd() != null && !d.contentMd().isBlank()) {
                digestByChapterNo.put(d.chapterNo(), d.contentMd());
            }
        }
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (ChapterDTO c : chapters) {
            String digest = digestByChapterNo.get(c.getChapterNo());
            StringBuilder line = new StringBuilder();
            line.append("第").append(c.getChapterNo()).append("章 ").append(nullToEmpty(c.getTitle()));
            if (c.getGoal() != null && !c.getGoal().isBlank()) {
                line.append("（目标：").append(c.getGoal().strip()).append("）");
            }
            line.append("\n");
            if (digest != null && !digest.isBlank()) {
                line.append(digest.strip()).append("\n");
            } else if (c.getHook() != null && !c.getHook().isBlank()) {
                line.append("章末钩子：").append(c.getHook().strip()).append("\n");
            }
            if (chars + line.length() > DIGEST_BLOCK_MAX_CHARS) {
                sb.append("（后续章节摘要省略）\n");
                break;
            }
            sb.append(line).append("\n");
            chars += line.length();
        }
        return sb.toString().strip();
    }

    /** 已有素材卡摘要（世界观合成的「设定类实体卡」输入）。 */
    private String cardBlock(long novelId) {
        StringBuilder sb = new StringBuilder();
        for (MaterialCardDTO c : cardData.listByNovel(novelId, null)) {
            sb.append("- ").append(kindLabel(c.getKind())).append("｜").append(c.getName());
            if (c.getSummary() != null && !c.getSummary().isBlank()) {
                sb.append("：").append(c.getSummary().strip());
            }
            if (c.isPinned()) {
                sb.append("（常驻）");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case MaterialCardDTO.KIND_CHARACTER -> "角色";
            case MaterialCardDTO.KIND_ITEM -> "物品";
            case MaterialCardDTO.KIND_LOCATION -> "地点";
            case MaterialCardDTO.KIND_LANDMARK -> "地标";
            case MaterialCardDTO.KIND_PHENOMENON -> "现象";
            case MaterialCardDTO.KIND_DISASTER -> "灾害";
            case MaterialCardDTO.KIND_ORG -> "组织";
            default -> "其他";
        };
    }

    // ===== 输出解析（纯函数，单测锁定） =====

    /** 模型输出 → 素材卡草稿列表：kind 归一（未知归 misc）、去重（同名同类）、截断到上限。 */
    List<CardDraft> parseCards(JsonNode node) {
        List<CardDraft> out = new ArrayList<>();
        JsonNode cards = node.path("cards");
        if (!cards.isArray()) {
            return out;
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (JsonNode c : cards) {
            String name = text(c, "name");
            if (name.isBlank()) {
                continue;
            }
            String kind = normalizeKind(text(c, "kind"));
            if (!seen.add(kind + "\u0000" + name)) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (JsonNode a : c.path("aliases")) {
                if (a.isTextual() && !a.asText().isBlank() && !a.asText().strip().equals(name)) {
                    aliases.add(a.asText().strip());
                }
            }
            Integer sourceChapter = c.path("sourceChapter").isNumber() ? c.path("sourceChapter").asInt() : null;
            out.add(new CardDraft(kind, name, aliases, text(c, "summary"), text(c, "content"),
                    c.path("pinned").asBoolean(false), sourceChapter));
            if (out.size() >= MAX_CARDS) {
                break;
            }
        }
        return out;
    }

    /** 大纲 JSON（premise/主线/arcs/主题/结局）→ Markdown 文本；缺字段的段落跳过。 */
    String renderOutline(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        String title = text(node, "title");
        sb.append("# 全书大纲").append(title.isBlank() ? "" : "：《" + title + "》").append("\n\n");
        appendSection(sb, "一句话前提", text(node, "premise"));
        appendSection(sb, "主线", text(node, "mainline"));
        appendSection(sb, "主题", text(node, "theme"));
        appendSection(sb, "结局走向", text(node, "ending"));
        JsonNode arcs = node.path("arcs");
        if (arcs.isArray() && !arcs.isEmpty()) {
            sb.append("## 分卷弧线\n");
            for (JsonNode arc : arcs) {
                String name = text(arc, "title");
                String summary = text(arc, "summary");
                if (name.isBlank() && summary.isBlank()) {
                    continue;
                }
                sb.append("- **").append(name.isBlank() ? "（未命名）" : name).append("**")
                        .append(summary.isBlank() ? "" : "：" + summary).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private void appendSection(StringBuilder sb, String heading, String body) {
        if (!body.isBlank()) {
            sb.append("## ").append(heading).append("\n").append(body).append("\n\n");
        }
    }

    static String normalizeKind(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return KINDS.contains(v) ? v : MaterialCardDTO.KIND_MISC;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() || v.isNumber() ? v.asText().strip() : "";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n（截断）";
    }

    /** 模型偶尔会裹一层 ``` 围栏——去掉，别把围栏写进大纲/世界观正文。 */
    static String stripFence(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstBreak = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                return t.substring(firstBreak + 1, lastFence).strip();
            }
        }
        return t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private NovelDTO requireNovel(long novelId) {
        NovelDTO novel = novelData.getById(novelId);
        if (novel == null) {
            throw new com.zzdzz.novelgen.common.web.BizException(
                    com.zzdzz.novelgen.common.web.ErrorCode.NOT_FOUND, "作品不存在: " + novelId);
        }
        return novel;
    }

    private void canonUpsert(long novelId, String kind, String name, String content) {
        Long id = canonData.findId(novelId, kind, name);
        if (id == null) {
            canonData.insert(novelId, kind, name, content);
        } else {
            canonData.updateContent(id, content);
        }
    }

    /** 素材卡草稿（解析与落库之间的中间结构，便于单测）。 */
    record CardDraft(String kind, String name, List<String> aliases, String summary, String content,
                     boolean pinned, Integer sourceChapter) {
    }
}
