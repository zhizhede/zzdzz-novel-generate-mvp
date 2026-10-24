package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.DomainCellMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.DomainCellDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.vo.derive.DomainVO;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ④c 域格归纳 + 类别浓度词表（v3.3）。
 *
 * <p>域格 = 角色关系能落位的具体场所/情境单元（"食堂 / 小卖部"），跨书共享的维表；
 * ⑤抽样按 cats⊇主类别 + 加权 seed 抽 1（§5.2.6），⑧质检拿 counter_words 做反域 grep。
 * 本服务从本书素材的标签层归纳（LLM），落 domain_cell（jsonb/text[] 走 insertFull）；
 * 同 (grp,name) 已存在则跳过（多书归纳是增量合并，不覆盖——ref_ids 留首归纳来源）。
 * **v3.3**：域格落库后按本书 category_tags 生成 CATEGORY_TERM 类别浓度词表（⑧第 6 项依赖）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveDomainService {

    public static final String NODE_DOMAIN = "derive_domain";
    public static final String NODE_CATEGORY_TERMS = "derive_category_terms";

    /** 域格归纳提示词（v3.2 §4c 未给正文，此为系统侧实现；输出必须对象根）。 */
    static final String PROMPT = """
            你在为素材库归纳"域格"——角色关系能落位的具体场所/情境单元。依据下列素材标签层与类别：
            - 输出 3~8 个域格，每格字段：
              name：≤10字（如"食堂""医务室"），不写专名；
              grp：域组（如"校园域""都市域"，同类场景同组）；
              cats：承载类别 {"类别":0~1}，至少覆盖输入类别之一，0~1；
              beat_types：该格可承载的拍型数组（从 铺垫|情绪|事件|反转|抉择|收束 选 1~4 个）；
              relation_note：本格可落位的关系动作，1~2 句，不带专名；
              counter_words：与该格气质冲突的反域词数组 3~6 个（供质检 grep，词≤4字）；
              ref_ids：归纳依据——引用输入中世界条目的 code（1~3 个，确无依据给空数组）。
            域格必须从素材可推，不发明无关场景；不写来源书名与专名。
            只输出 JSON 对象：{"cells":[{"name":"","grp":"","cats":{"类别":0.5},
            "beat_types":["铺垫"],"relation_note":"","counter_words":["词"],"ref_ids":["W-P01"]}]}
            """;

    /** v3.3 类别浓度词表提示词（⑧第 6 项每章≥3 命中的数据源）。 */
    static final String CATEGORY_TERMS_PROMPT = """
            你在为质检生成"类别浓度词表"。对给定的每个类别，输出 10~20 个该类别作品正文中高频出现的特征词（供逐章浓度 grep，每章≥3 命中为达标）：
            - 词≤4 字，名词/动词/短语均可；必须是该类别确实高频的词（如言情：告白、牵手、心跳）；
            - 不要专名、不要虚词（的/了/很）、不要过宽多义词（东西、时候）；
            - 各类别独立成数组，键与输入类别一致。
            只输出 JSON 对象：{"terms":{"类别":["词"],"…":["词"]}}
            """;

    private final NovelMapper novelMapper;
    private final AssetOutlineMapper outlineMapper;
    private final AssetWorldMapper worldMapper;
    private final DomainCellMapper domainCellMapper;
    private final WordlistMapper wordlistMapper;
    private final LlmJson llmJson;

    /** ④c 归纳本书域格（幂等：同 grp+name 已存在跳过）。 */
    public DomainVO domains(long novelId) {
        NovelDO novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "书不存在: " + novelId);
        }
        List<AssetWorldDO> worlds = worldMapper.selectList(
                new LambdaQueryWrapper<AssetWorldDO>().eq(AssetWorldDO::getSrcNovelId, novelId));
        List<AssetOutlineDO> arcs = outlineMapper.selectList(
                new LambdaQueryWrapper<AssetOutlineDO>().eq(AssetOutlineDO::getSrcNovelId, novelId));
        if (worlds.isEmpty() && arcs.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本书还没有素材，先走②③");
        }

        StringBuilder input = new StringBuilder("类别：")
                .append(novel.getCategoryTags() == null ? "{}" : novel.getCategoryTags())
                .append("\n世界条目：\n");
        for (AssetWorldDO w : worlds) {
            input.append(w.getCode()).append(" [").append(w.getKind()).append("] ")
                  .append(w.getLabelText() == null ? w.getExtractedText() : w.getLabelText()).append('\n');
        }
        input.append("弧：\n");
        for (AssetOutlineDO a : arcs) {
            input.append(a.getCode()).append(" ")
                  .append(a.getLabelText() == null ? a.getExtractedText() : a.getLabelText()).append('\n');
        }

        List<CellDraft> cells = llmJson.ask(
                new LlmPort.ChatRequest(NODE_DOMAIN, novelId, null,
                        List.of(LlmPort.Message.system(PROMPT), LlmPort.Message.user(input.toString())),
                        0.3),
                DeriveDomainService::parseCells, 3);

        // 已有 (grp,name) 集合 → 去重跳过（域格量级小，全表拉一次即可）
        Set<String> existing = new HashSet<>();
        for (DomainCellDO d : domainCellMapper.selectList(new LambdaQueryWrapper<>())) {
            existing.add(d.getGrp() + "|" + d.getName());
        }

        int created = 0;
        int skipped = 0;
        for (CellDraft c : cells) {
            if (existing.contains(c.grp() + "|" + c.name())) {
                skipped++;
                continue;
            }
            DomainCellDO row = new DomainCellDO();
            row.setName(c.name());
            row.setGrp(c.grp());
            row.setCats(c.catsJson());
            row.setBeatTypes(toPgArray(c.beatTypes()));
            row.setRelationNote(c.relationNote());
            row.setCounterWords(toPgArray(c.counterWords()));
            row.setRefIds(c.refIdsJson());
            row.setEnabled(true);
            row.setSort(created);
            domainCellMapper.insertFull(row);
            existing.add(c.grp() + "|" + c.name());
            created++;
        }
        log.info("④c 域格：novelId={} 归纳 {} 个，新建 {} 跳过 {}", novelId, cells.size(), created, skipped);

        // ---- v3.3：按书类别生成 CATEGORY_TERM（⑧第 6 项依赖；无类别 tags 则跳过） ----
        int categoryTermsAdded = 0;
        String tagsJson = novel.getCategoryTags();
        Set<String> cats = parseTagKeys(tagsJson);
        if (!cats.isEmpty()) {
            Map<String, List<String>> terms = llmJson.ask(
                    new LlmPort.ChatRequest(NODE_CATEGORY_TERMS, novelId, null,
                            List.of(LlmPort.Message.system(CATEGORY_TERMS_PROMPT),
                                    LlmPort.Message.user("类别：" + tagsJson)),
                            0.3),
                    root -> parseCategoryTerms(root, cats), 3);
            categoryTermsAdded = addCategoryTerms(terms);
        }
        log.info("④c 类别浓度词：novelId={} 类别 {} 产出 {} 词", novelId, cats, categoryTermsAdded);
        return new DomainVO(novelId, created, skipped, categoryTermsAdded);
    }

    // ---- v3.3 CATEGORY_TERM ----

    /** 解析 category_tags 的键集合（坏 JSON/空按无类别处理）。 */
    static Set<String> parseTagKeys(String tagsJson) {
        Set<String> out = new LinkedHashSet<>();
        if (tagsJson == null || tagsJson.isBlank()) {
            return out;
        }
        try {
            JsonNode n = new ObjectMapper().readTree(tagsJson);
            if (n.isObject()) {
                n.fieldNames().forEachRemaining(out::add);
            }
        } catch (Exception e) {
            log.warn("category_tags 解析失败，跳过 CATEGORY_TERM：{}", e.getMessage());
        }
        return out;
    }

    static Map<String, List<String>> parseCategoryTerms(JsonNode root, Set<String> expected) {
        JsonNode terms = root.path("terms");
        if (!terms.isObject() || terms.isEmpty()) {
            throw new LlmJson.Bad("terms 必须是非空对象");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String cat : expected) {
            JsonNode arr = terms.path(cat);
            List<String> words = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String v = n.asText("").trim();
                    if (!v.isEmpty() && v.length() <= 4) {
                        words.add(v);
                    }
                }
            }
            if (!words.isEmpty()) {
                out.put(cat, words);
            }
        }
        if (out.isEmpty()) {
            throw new LlmJson.Bad("未产出任何类别词（期望类别：" + expected + "）");
        }
        return out;
    }

    /** 写入 wordlist(kind=CATEGORY_TERM)；按 (kind,word) 去重。 */
    int addCategoryTerms(Map<String, List<String>> termsByCat) {
        Set<String> existing = wordlistMapper.selectList(
                        new LambdaQueryWrapper<WordlistDO>().eq(WordlistDO::getKind, "CATEGORY_TERM"))
                .stream().map(WordlistDO::getWord).filter(w -> w != null && !w.isBlank()).collect(Collectors.toSet());
        int added = 0;
        for (Map.Entry<String, List<String>> e : termsByCat.entrySet()) {
            for (String w : e.getValue()) {
                if (existing.contains(w)) {
                    continue;
                }
                WordlistDO row = new WordlistDO();
                row.setKind("CATEGORY_TERM");
                row.setWord(w);
                row.setCategory(e.getKey());
                wordlistMapper.insert(row);
                existing.add(w);
                added++;
            }
        }
        return added;
    }

    /** 字符串数组 → PG 数组字面量 "{a,b}"（insertFull 里 ::text[]）。 */
    static String toPgArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            // 数组字面量内逗号/引号/反斜杠需转义——词都是短中文词，仅做基础防护
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    // ---- 解析 ----

    record CellDraft(String name, String grp, String catsJson, List<String> beatTypes,
                     String relationNote, List<String> counterWords, String refIdsJson) {
    }

    static List<CellDraft> parseCells(JsonNode root) {
        JsonNode arr = root.path("cells");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new LlmJson.Bad("cells 必须是非空数组");
        }
        if (arr.size() < 3 || arr.size() > 8) {
            throw new LlmJson.Bad("cells 必须 3~8 个，当前 " + arr.size());
        }
        Set<String> beatTypes = Set.of("铺垫", "情绪", "事件", "反转", "抉择", "收束");
        List<CellDraft> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode c = arr.get(i);
            String name = c.path("name").asText("").trim();
            String grp = c.path("grp").asText("").trim();
            JsonNode cats = c.path("cats");
            if (name.isEmpty() || grp.isEmpty()) {
                throw new LlmJson.Bad("cells[" + i + "] 缺 name/grp");
            }
            if (name.length() > 10) {
                throw new LlmJson.Bad(name + " name 超 10 字");
            }
            if (!cats.isObject() || cats.isEmpty()) {
                throw new LlmJson.Bad(name + " 缺 cats 对象");
            }
            for (JsonNode v : cats) {
                double d = v.asDouble(-1);
                if (d < 0 || d > 1) {
                    throw new LlmJson.Bad(name + " cats 取值必须 0~1");
                }
            }
            List<String> bts = new ArrayList<>();
            for (JsonNode b : c.path("beat_types")) {
                String v = b.asText("");
                if (!beatTypes.contains(v)) {
                    throw new LlmJson.Bad(name + " beat_types 非法: " + v);
                }
                bts.add(v);
            }
            if (bts.isEmpty() || bts.size() > 4) {
                throw new LlmJson.Bad(name + " beat_types 须 1~4 个");
            }
            List<String> counter = new ArrayList<>();
            for (JsonNode w : c.path("counter_words")) {
                String v = w.asText("").trim();
                if (!v.isEmpty() && v.length() <= 4) {
                    counter.add(v);
                }
            }
            if (counter.size() < 3) {
                throw new LlmJson.Bad(name + " counter_words 至少 3 个");
            }
            out.add(new CellDraft(name, grp, cats.toString(), bts,
                    c.path("relation_note").asText(""), counter,
                    c.path("refIds").isArray() ? c.path("refIds").toString()
                            : c.path("ref_ids").toString()));
        }
        return out;
    }
}
