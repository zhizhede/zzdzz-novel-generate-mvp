package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.vo.derive.DeindexVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ③无指向化（LLM + 词表，v3.2 §③）。
 *
 * <p>四步清洗（v3.2 §③ 权威口径）：①去实体三层（事件/场景/价值词、源领域词、时间量纲锚）
 * ②角色与物位泛型占位 ③压成 [功能标签] + 关系式 ⇒ 状态变化 ④自检门（不含来源身份词）。
 *
 * <p>每书 2 次 LLM 调用：批量清洗（弧/拍/世界混合，code 对齐校验喂回重试）+ 源词表提取
 * （identity→BLACKLIST、domain→DOMAIN_WORD；title/author/platform 由脚本直接进黑名单）。
 * 落库为**覆盖式幂等**：label_text/func_tag 重跑即覆盖，词表按 (kind,word) 去重——
 * 故整个方法不套事务（LLM 调用不得进事务），失败中断可直接重跑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveDeindexService {

    public static final String NODE_CLEAN = "derive_deindex";
    public static final String NODE_WORDS = "derive_source_words";

    /** 喂给词表提取的正文上限。 */
    static final int WORDS_INPUT_MAX_CHARS = 60_000;

    /** 清洗提示词：四步清洗为 v3.2 §③ 原文口径，追加输入输出 JSON 契约。 */
    static final String CLEAN_PROMPT = """
            你在为素材库做无指向化清洗。输入是抽取稿（带来源痕迹），输出只有标签层。四步清洗：
            ①去实体三层：去掉具体事件/场景/价值评判词、源领域词（如该题材的器物行话）、时间量纲锚（"三年""半月"这类）。
            ②角色与物位泛型占位：人名→主角/对手/长辈/同侪等泛型，物→类别词（具体载具→"载具"，账册→"凭证"）。
            ③拍与弧压成 "[功能标签] + 关系式 ⇒ 状态变化"；世界观条目压成 "条件 → 后果"（泛型条件）。
            ④自检：不得出现来源书名、人名、地名、船名等身份词；不得复述原文句子；标签不含源领域词。
            对每个输入 code 输出清洗结果；tags 是功能标签词（1~3 个中文词，弧/拍必给，世界观不给）。
            只输出一个 JSON 对象：{"items":[{"code":"O-P01","label":"[标签] 关系式 ⇒ 状态变化","tags":["功能词"]}]}
            """;

    /** 源词表提取提示词：identity 进黑名单、domain 进领域词表。 */
    static final String WORDS_PROMPT = """
            你在为素材库抽取源书词表，只依据输入原文。输出两类词：
            identity：来源身份词——人名、地名、船名/机构名等专名、书名、作者名（各取词干，不含普通名词）。
            domain：源领域词——该题材的器物/行话/场景名词（如航海域的缆绳、罗盘、舷窗）。
            每类 10~40 个，全是词或短语（≤6字），不要句子、不要分类解释。
            只输出 JSON：{"identity":["词"],"domain":["词"]}
            """;

    private final NovelMapper novelMapper;
    private final AssetOutlineMapper outlineMapper;
    private final AssetBeatMapper beatMapper;
    private final AssetWorldMapper worldMapper;
    private final WordlistMapper wordlistMapper;
    private final com.zzdzz.novelgen.derive.mapper.SourceChapterMapper chapterMapper;
    private final LlmJson llmJson;

    /** ③无指向化：批量清洗覆盖 label_text/func_tag + 源词表入库。 */
    public DeindexVO deindex(long novelId) {
        NovelDO novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "书不存在: " + novelId);
        }
        List<AssetOutlineDO> arcs = outlineMapper.selectList(
                new LambdaQueryWrapper<AssetOutlineDO>().eq(AssetOutlineDO::getSrcNovelId, novelId));
        List<AssetBeatDO> beats = beatMapper.selectList(
                new LambdaQueryWrapper<AssetBeatDO>().eq(AssetBeatDO::getSrcNovelId, novelId));
        List<AssetWorldDO> worlds = worldMapper.selectList(
                new LambdaQueryWrapper<AssetWorldDO>().eq(AssetWorldDO::getSrcNovelId, novelId));
        if (arcs.isEmpty() && beats.isEmpty() && worlds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本书还没有抽取稿，先走②结构抽取");
        }

        // ---- LLM ①：批量清洗（事务外）----
        List<Item> items = new ArrayList<>();
        for (AssetOutlineDO a : arcs) {
            items.add(new Item(a.getCode(), "arc", a.getExtractedText()));
        }
        for (AssetBeatDO b : beats) {
            items.add(new Item(b.getCode(), "beat", b.getExtractedText()));
        }
        for (AssetWorldDO w : worlds) {
            items.add(new Item(w.getCode(), "world", w.getExtractedText()));
        }
        Map<String, CleanOut> cleaned = llmJson.ask(
                new LlmPort.ChatRequest(NODE_CLEAN, novelId, null,
                        List.of(LlmPort.Message.system(CLEAN_PROMPT),
                                LlmPort.Message.user(toInputJson(items))),
                        0.2),
                DeriveDeindexService::parseCleaned, 3);

        // ---- 落库：覆盖式 ----
        int arcN = 0;
        int beatN = 0;
        int worldN = 0;
        for (AssetOutlineDO a : arcs) {
            CleanOut c = cleaned.get(a.getCode());
            if (c == null) {
                continue;
            }
            a.setLabelText(c.label());
            a.setFuncTag(c.funcTag());
            a.setUpdatedTime(OffsetDateTime.now());
            outlineMapper.updateById(a);
            arcN++;
        }
        for (AssetBeatDO b : beats) {
            CleanOut c = cleaned.get(b.getCode());
            if (c == null) {
                continue;
            }
            b.setLabelText(c.label());
            b.setFuncTag(c.funcTag());
            b.setUpdatedTime(OffsetDateTime.now());
            beatMapper.updateById(b);
            beatN++;
        }
        for (AssetWorldDO w : worlds) {
            CleanOut c = cleaned.get(w.getCode());
            if (c == null) {
                continue;
            }
            w.setLabelText(c.label());
            w.setUpdatedTime(OffsetDateTime.now());
            worldMapper.updateById(w);
            worldN++;
        }

        // ---- LLM ②：源词表（事务外）+ 脚本直加身份词 ----
        Set<String> identity = new HashSet<>();
        Set<String> domain = new HashSet<>();
        if (novel.getTitle() != null) {
            identity.add(novel.getTitle().strip());
        }
        if (novel.getAuthor() != null) {
            identity.add(novel.getAuthor().strip());
        }
        if (novel.getPlatform() != null) {
            identity.add(novel.getPlatform().strip());
        }
        String bookText = DeriveExtractService.joinChapters(new ArrayList<>(loadChapters(novelId)));
        if (!bookText.isBlank()) {
            WordPack pack = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_WORDS, novelId, null,
                            List.of(LlmPort.Message.system(WORDS_PROMPT),
                                    LlmPort.Message.user(truncate(bookText))),
                            0.1),
                    DeriveDeindexService::parseWords, 2);
            identity.addAll(pack.identity());
            domain.addAll(pack.domain());
        }
        int blacklistAdded = addWordlist("BLACKLIST", identity);
        int domainAdded = addWordlist("DOMAIN_WORD", domain);

        log.info("③无指向化：novelId={} 弧{} 拍{} 世界{} 黑名单+{} 领域词+{}",
                novelId, arcN, beatN, worldN, blacklistAdded, domainAdded);
        return new DeindexVO(novelId, arcN, beatN, worldN, blacklistAdded, domainAdded);
    }

    // ---- 章节读取（词表用）----

    List<com.zzdzz.novelgen.model.entity.derive.SourceChapterDO> loadChapters(long novelId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<com.zzdzz.novelgen.model.entity.derive.SourceChapterDO>()
                .eq(com.zzdzz.novelgen.model.entity.derive.SourceChapterDO::getNovelId, novelId)
                .orderByAsc(com.zzdzz.novelgen.model.entity.derive.SourceChapterDO::getSeq));
    }

    static String truncate(String s) {
        return s.length() > WORDS_INPUT_MAX_CHARS ? s.substring(0, WORDS_INPUT_MAX_CHARS) : s;
    }

    // ---- 词表去重插入 ----

    int addWordlist(String kind, Set<String> words) {
        Set<String> existing = new HashSet<>();
        for (WordlistDO w : wordlistMapper.selectList(
                new LambdaQueryWrapper<WordlistDO>().eq(WordlistDO::getKind, kind))) {
            existing.add(w.getWord());
        }
        int added = 0;
        for (String word : words) {
            if (word == null || word.isBlank() || existing.contains(word)) {
                continue;
            }
            WordlistDO row = new WordlistDO();
            row.setKind(kind);
            row.setWord(word.strip());
            wordlistMapper.insert(row);
            existing.add(row.getWord());
            added++;
        }
        return added;
    }

    // ---- 输入构造与解析 ----

    record Item(String code, String kind, String text) {
    }

    record CleanOut(String label, String funcTag) {
    }

    static String toInputJson(List<Item> items) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"code\":\"").append(esc(it.code()))
              .append("\",\"kind\":\"").append(it.kind())
              .append("\",\"text\":\"").append(esc(it.text())).append("\"}");
        }
        return sb.append("]}").toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static Map<String, CleanOut> parseCleaned(JsonNode root) {
        JsonNode arr = root.path("items");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new LlmJson.Bad("items 必须是非空数组");
        }
        Map<String, CleanOut> out = new HashMap<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode it = arr.get(i);
            String code = it.path("code").asText("").trim();
            String label = it.path("label").asText("").trim();
            if (code.isEmpty() || label.isEmpty()) {
                throw new LlmJson.Bad("items[" + i + "] 缺 code 或 label");
            }
            StringBuilder tags = new StringBuilder();
            JsonNode tagArr = it.path("tags");
            if (tagArr.isArray()) {
                for (JsonNode t : tagArr) {
                    String v = t.asText("").trim();
                    if (!v.isEmpty()) {
                        tags.append('[').append(v).append(']');
                    }
                }
            }
            out.put(code, new CleanOut(label, tags.length() == 0 ? null : tags.toString()));
        }
        return out;
    }

    record WordPack(Set<String> identity, Set<String> domain) {
    }

    static WordPack parseWords(JsonNode root) {
        Set<String> identity = readStringArray(root.path("identity"));
        Set<String> domain = readStringArray(root.path("domain"));
        if (identity.isEmpty() && domain.isEmpty()) {
            throw new LlmJson.Bad("identity/domain 至少一类非空");
        }
        return new WordPack(identity, domain);
    }

    static Set<String> readStringArray(JsonNode arr) {
        Set<String> out = new HashSet<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String v = n.asText("").trim();
                if (!v.isEmpty() && v.length() <= 6) {
                    out.add(v);
                }
            }
        }
        return out;
    }
}
