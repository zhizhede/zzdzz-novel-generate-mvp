package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import com.zzdzz.novelgen.model.vo.derive.GrepVO;
import com.zzdzz.novelgen.model.vo.derive.LabelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ④入库打标（脚本 + LLM 批量，v3.2 §④）。
 *
 * <ul>
 *   <li><b>4a 全量 grep</b>：wordlist（BLACKLIST+DOMAIN_WORD）对全池 label_text/func_tag 做包含匹配，
 *       命中即列出（打回③的依据）。脚本侧无 LLM。—— 编号在 ② 插入时已按全局 O-Pxx 分配，4a 不再重排。</li>
 *   <li><b>4b 批量打标</b>：v3.2 §4b 提示词原文（规则不改字），JSON 数组输出；pacing 锚点在解析层校验、
 *       DB CHECK 二次把关（pacing_src=LLM 强制锚点）；arc 多选→arc_fit 权重脚本后处理（首选1.0/次选0.5/其余0.0）。
 *       标签只进 label_index，不回写条目正文；重打=先删本书已有行再插（(entry_type,entry_type_id) 复合唯一）。</li>
 *   <li><b>4c 域格</b>：domain_cell 生成不在本服务（Phase3 ⑤采样前接入，见架构 §8）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveLabelingService {

    public static final String NODE_LABEL = "derive_label";

    /** v3.2 §4b 提示词原文 + JSON 输出契约（规则段不改字）。输出必须是对象根——LlmJson 截取首尾大括号，裸数组提取不出来。 */
    static final String LABEL_PROMPT = """
            对下列素材逐条打标，输出一个 JSON 对象（无其他文字）：
            {"labels":[{"code":"S-P44","cat":{"言情":0.0,"悬疑":0.0,"校园":0.0},"beat_type":"铺垫","pacing":0.75,"arc":["开头","中段"],"standalone":false}]}
            规则：
            - cat：关系母题亲和 0~1，域中性不影响打标。键用通用类别词（从素材真实气质选，
              如 言情/悬疑/校园/冒险/科幻/奇幻/甜宠/历史/现实…），只列相关的 1~4 个键；
              **每条至少一个键 >0（全 0 不合规会被打回）**；参考源书类别优先，气质相近可另选。
            - beat_type：单选，只打该拍的主导功能（铺垫|情绪|事件|反转|抉择|收束）；世界观条目不打本项（给 null）。
            - pacing：只许五锚点 0.00/0.25/0.50/0.75/1.00（0 慢 1 快）；事件/反转取
              0.75~1.00，铺垫/情绪取 0.00~0.25，两可取 0.50；禁止锚点以外的取值
              （如 0.63）——两位小数仅限系统派生与人工微调，LLM 不得给。
            - arc：多选 0~2 个位置（开头|中段|转折|收尾，可不选）；standalone＝能否独立成篇。
            - 标签不写回条目正文。
            输入 {"items":[{"code":"...","kind":"arc|beat|world","text":"标签层文案"}]}；
            输出 labels 条数与输入一致、code 一一对应，kind=world 的 beat_type 给 null。
            """;

    private final AssetOutlineMapper outlineMapper;
    private final AssetBeatMapper beatMapper;
    private final AssetWorldMapper worldMapper;
    private final LabelIndexMapper labelIndexMapper;
    private final WordlistMapper wordlistMapper;
    private final com.zzdzz.novelgen.derive.mapper.NovelMapper novelMapper;
    private final LlmJson llmJson;

    /** 源书类别 jsonb（打标参考词表）；查无此书返回 {}。 */
    String loadCategoryTags(long novelId) {
        com.zzdzz.novelgen.model.entity.derive.NovelDO n = novelMapper.selectById(novelId);
        return n == null || n.getCategoryTags() == null ? "{}" : n.getCategoryTags();
    }

    // ================= 4a · 黑名单/领域词全量 grep =================

    /** 对本书（novelId≥0）或全池（novelId<0）的标签层做 wordlist 包含匹配。 */
    public GrepVO grep(long novelId) {
        List<String> words = wordlistMapper.selectList(new LambdaQueryWrapper<WordlistDO>()
                        .in(WordlistDO::getKind, List.of("BLACKLIST", "DOMAIN_WORD")))
                .stream().map(WordlistDO::getWord).filter(w -> w != null && !w.isBlank()).toList();

        List<GrepVO.Hit> hits = new ArrayList<>();
        int scanned = 0;

        List<AssetOutlineDO> arcs = outlineMapper.selectList(
                novelId < 0 ? new LambdaQueryWrapper<AssetOutlineDO>()
                        : new LambdaQueryWrapper<AssetOutlineDO>().eq(AssetOutlineDO::getSrcNovelId, novelId));
        List<AssetBeatDO> beats = beatMapper.selectList(
                novelId < 0 ? new LambdaQueryWrapper<AssetBeatDO>()
                        : new LambdaQueryWrapper<AssetBeatDO>().eq(AssetBeatDO::getSrcNovelId, novelId));
        for (AssetOutlineDO a : arcs) {
            scanned++;
            grepRow(a.getCode(), a.getLabelText(), a.getFuncTag(), words, hits);
        }
        for (AssetBeatDO b : beats) {
            scanned++;
            grepRow(b.getCode(), b.getLabelText(), b.getFuncTag(), words, hits);
        }
        List<AssetWorldDO> worlds = worldMapper.selectList(
                novelId < 0 ? new LambdaQueryWrapper<AssetWorldDO>()
                        : new LambdaQueryWrapper<AssetWorldDO>().eq(AssetWorldDO::getSrcNovelId, novelId));
        for (AssetWorldDO w : worlds) {
            scanned++;
            grepRow(w.getCode(), w.getLabelText(), null, words, hits);
        }
        log.info("④a grep：novelId={} 扫描 {} 条，命中 {} 处", novelId, scanned, hits.size());
        return new GrepVO(novelId, scanned, hits);
    }

    private static void grepRow(String code, String label, String funcTag,
                                List<String> words, List<GrepVO.Hit> hits) {
        scanField(code, "label_text", label, words, hits);
        scanField(code, "func_tag", funcTag, words, hits);
    }

    private static void scanField(String code, String field, String value,
                                  List<String> words, List<GrepVO.Hit> hits) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String w : words) {
            if (value.contains(w)) {
                hits.add(new GrepVO.Hit(code, field, w));
            }
        }
    }

    // ================= 4b · 批量打标 =================

    /** 对本书全量素材批量打标：LLM → 校验 → 删旧行 → insertFull。 */
    public LabelVO label(long novelId) {
        List<AssetOutlineDO> arcs = outlineMapper.selectList(
                new LambdaQueryWrapper<AssetOutlineDO>().eq(AssetOutlineDO::getSrcNovelId, novelId));
        List<AssetBeatDO> beats = beatMapper.selectList(
                new LambdaQueryWrapper<AssetBeatDO>().eq(AssetBeatDO::getSrcNovelId, novelId));
        List<AssetWorldDO> worlds = worldMapper.selectList(
                new LambdaQueryWrapper<AssetWorldDO>().eq(AssetWorldDO::getSrcNovelId, novelId));
        if (arcs.isEmpty() && beats.isEmpty() && worlds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本书还没有素材，先走②③");
        }

        // 输入：标签层文案（未清洗的 label_text 为 NULL 时跳过并提示）
        List<Item> items = new ArrayList<>();
        Map<String, Long> idByCode = new HashMap<>();
        Map<String, String> typeByCode = new HashMap<>();
        for (AssetOutlineDO a : arcs) {
            if (a.getLabelText() != null) {
                items.add(new Item(a.getCode(), "arc", a.getLabelText()));
                idByCode.put(a.getCode(), a.getId());
                typeByCode.put(a.getCode(), "OUTLINE");
            }
        }
        for (AssetBeatDO b : beats) {
            if (b.getLabelText() != null) {
                items.add(new Item(b.getCode(), "beat", b.getLabelText()));
                idByCode.put(b.getCode(), b.getId());
                typeByCode.put(b.getCode(), "BEAT");
            }
        }
        for (AssetWorldDO w : worlds) {
            if (w.getLabelText() != null) {
                items.add(new Item(w.getCode(), "world", w.getLabelText()));
                idByCode.put(w.getCode(), w.getId());
                typeByCode.put(w.getCode(), "WORLD");
            }
        }
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本书还没有标签层文案，先走③无指向化");
        }

        // 源书类别参考（cat 键优先贴源书类别，避免 LLM 照抄示例词表打全 0）
        String catRef = loadCategoryTags(novelId);

        List<LabelDraft> drafts = llmJson.ask(
                new LlmPort.ChatRequest(NODE_LABEL, novelId, null,
                        List.of(LlmPort.Message.system(LABEL_PROMPT),
                                LlmPort.Message.user("源书类别参考：" + catRef + "\n"
                                        + DeriveDeindexService.toInputJson(toServiceItems(items)))),
                        0.2),
                DeriveLabelingService::parseLabels, 3);

        // 删本书旧行（复合唯一：重打=替换，jsonb 更新不便走 MP → 删了重插）
        if (!arcs.isEmpty()) {
            labelIndexMapper.delete(new LambdaQueryWrapper<LabelIndexDO>()
                    .eq(LabelIndexDO::getEntryType, "OUTLINE")
                    .in(LabelIndexDO::getEntryTypeId, arcs.stream().map(AssetOutlineDO::getId).toList()));
        }
        if (!beats.isEmpty()) {
            labelIndexMapper.delete(new LambdaQueryWrapper<LabelIndexDO>()
                    .eq(LabelIndexDO::getEntryType, "BEAT")
                    .in(LabelIndexDO::getEntryTypeId, beats.stream().map(AssetBeatDO::getId).toList()));
        }
        if (!worlds.isEmpty()) {
            labelIndexMapper.delete(new LambdaQueryWrapper<LabelIndexDO>()
                    .eq(LabelIndexDO::getEntryType, "WORLD")
                    .in(LabelIndexDO::getEntryTypeId, worlds.stream().map(AssetWorldDO::getId).toList()));
        }

        int n = 0;
        for (LabelDraft d : drafts) {
            Long entryId = idByCode.get(d.code());
            if (entryId == null) {
                continue;
            }
            LabelIndexDO row = new LabelIndexDO();
            row.setEntryType(typeByCode.get(d.code()));
            row.setEntryTypeId(entryId);
            row.setCat(d.catJson());
            row.setBeatType(d.beatType());
            row.setPacing(d.pacing());
            row.setPacingSrc("LLM");
            row.setArcFit(arcFitJson(d.arc()));
            row.setStandalone(d.standalone());
            labelIndexMapper.insertFull(row);
            n++;
        }
        log.info("④b 打标：novelId={} 输入 {} 条，落 label_index {} 行", novelId, items.size(), n);
        return new LabelVO(novelId, n, drafts.size());
    }

    /** arc 多选 → arc_fit 权重（v3.2 4b 脚本后处理：首选1.0、次选0.5、其余0.0；四键齐全）。 */
    static String arcFitJson(List<String> arcSel) {
        Map<String, Double> fit = new LinkedHashMap<>();
        fit.put("开头", 0.0);
        fit.put("中段", 0.0);
        fit.put("转折", 0.0);
        fit.put("收尾", 0.0);
        if (arcSel != null) {
            int rank = 0;
            for (String sel : arcSel) {
                String key = normalizeArc(sel);
                if (key != null && fit.containsKey(key) && fit.get(key) == 0.0) {
                    fit.put(key, rank == 0 ? 1.0 : 0.5);
                    rank++;
                }
            }
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> e : fit.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    static String normalizeArc(String sel) {
        if (sel == null) {
            return null;
        }
        String s = sel.replace("可作", "").replace("位置", "").strip();
        return Set.of("开头", "中段", "转折", "收尾").contains(s) ? s : null;
    }

    // ---- 输入构造与解析 ----

    record Item(String code, String kind, String text) {
    }

    /** 与 DeriveDeindexService.Item 同构——复用其 JSON 构造。 */
    private static List<DeriveDeindexService.Item> toServiceItems(List<Item> items) {
        List<DeriveDeindexService.Item> out = new ArrayList<>();
        for (Item i : items) {
            out.add(new DeriveDeindexService.Item(i.code(), i.kind(), i.text()));
        }
        return out;
    }

    static final Set<String> BEAT_TYPES = Set.of("铺垫", "情绪", "事件", "反转", "抉择", "收束");
    static final List<BigDecimal> ANCHORS = List.of(
            new BigDecimal("0.00"), new BigDecimal("0.25"),
            new BigDecimal("0.50"), new BigDecimal("0.75"), new BigDecimal("1.00"));

    /** 锚点判定用 compareTo：LLM 输出数字 0.5 与锚点 0.50 值等但 equals 因精度不等（BigDecimal 经典坑）。 */
    static boolean isAnchor(BigDecimal p) {
        return ANCHORS.stream().anyMatch(a -> p.compareTo(a) == 0);
    }

    record LabelDraft(String code, String catJson, String beatType, BigDecimal pacing,
                      List<String> arc, boolean standalone) {
    }

    static List<LabelDraft> parseLabels(JsonNode root) {
        JsonNode arr = root.path("labels");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new LlmJson.Bad("输出必须是 {\"labels\":[...]} 且非空");
        }
        List<LabelDraft> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            String code = n.path("code").asText("").trim();
            if (code.isEmpty() || seen.contains(code)) {
                throw new LlmJson.Bad("第 " + i + " 条缺 code 或重复");
            }
            seen.add(code);
            JsonNode cat = n.path("cat");
            if (!cat.isObject() || cat.isEmpty()) {
                throw new LlmJson.Bad(code + " 缺 cat 对象");
            }
            double catMax = 0;
            for (JsonNode v : cat) {
                double d = v.asDouble(-1);
                if (d < 0 || d > 1) {
                    throw new LlmJson.Bad(code + " cat 取值必须 0~1");
                }
                catMax = Math.max(catMax, d);
            }
            if (catMax <= 0) {
                throw new LlmJson.Bad(code + " cat 不得全 0——按素材真实气质给至少一个 >0 的类别亲和");
            }
            String beatType = n.path("beat_type").isNull() || n.path("beat_type").asText("").isEmpty()
                    ? null : n.path("beat_type").asText();
            if (beatType != null && !BEAT_TYPES.contains(beatType)) {
                throw new LlmJson.Bad(code + " beat_type 非法: " + beatType);
            }
            BigDecimal pacing;
            try {
                pacing = new BigDecimal(n.path("pacing").asText());
            } catch (NumberFormatException e) {
                throw new LlmJson.Bad(code + " pacing 非数字");
            }
            if (!isAnchor(pacing)) {
                throw new LlmJson.Bad(code + " pacing 必须是五锚点之一（0.00/0.25/0.50/0.75/1.00），当前 " + pacing);
            }
            List<String> arc = new ArrayList<>();
            JsonNode arcArr = n.path("arc");
            if (arcArr.isArray()) {
                for (JsonNode a : arcArr) {
                    arc.add(a.asText(""));
                }
            }
            if (arc.size() > 2) {
                throw new LlmJson.Bad(code + " arc 最多选 2 个位置");
            }
            out.add(new LabelDraft(code, cat.toString(), beatType, pacing, arc,
                    n.path("standalone").asBoolean(false)));
        }
        return out;
    }
}
