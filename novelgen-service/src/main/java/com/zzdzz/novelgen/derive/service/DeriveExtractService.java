package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.SourceChapterMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.AssetBeatDO;
import com.zzdzz.novelgen.model.entity.derive.AssetOutlineDO;
import com.zzdzz.novelgen.model.entity.derive.AssetWorldDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.SourceChapterDO;
import com.zzdzz.novelgen.model.vo.derive.ExtractVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ②结构抽取（LLM · 抽取稿阶段，v3.2 §②）。
 *
 * <p>提示词正文 = v3.2 §② 原文（权威不改字），仅追加机器可读的输出格式（JSON schema）——
 * 与 zzdzz PromptCatalog 同款做法：doc 是规则权威，代码常量带输出契约。
 * 走 {@link LlmJson#ask}：解析失败/校验不过把原因喂回重试（≤3 轮），全量留档 llm_call_log（node=derive_extract）。
 *
 * <p>落库：asset_outline / asset_beat（parent_arc_id 保持弧→拍层级）/ asset_world（kind=RULE/FACT）。
 * code 在插入时按全局序号分配 O-Pxx/S-Pxx/W-Pxx（v3.2 ④a 的全局重编号提前到插入——
 * LLM 的本地编号 O01 各书重复，直接落库会撞唯一键；LLM 本地弧号只用于建 parent_arc_id 映射）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveExtractService {

    /** 台账节点名（llm_call_log.node / llm_node_config 路由键）。 */
    public static final String NODE = "derive_extract";

    /** 喂给 LLM 的正文上限：超出取头部（源书动辄数十万字，抽取看结构不吃全文尾部细节；常量可调）。 */
    static final int INPUT_MAX_CHARS = 120_000;

    /** v3.2 §② 提示词原文 + 输出格式（规则部分不得改动，见类注释）。 */
    static final String PROMPT = """
            你在为素材库做结构抽取，只依据输入原文，禁止复述原文句子。
            输出三部分：
            1.弧：把全书压成 6±2 条，每条：[标签] 一句话事件链 ⇒ 结果；粒度=各自能撑起一卷。
            2.拍：每条弧下拆 3–8 拍，每拍≤40字、自成一体（谁·做什么·发生什么·结果），拍与拍不依赖顺序。
            3.世界观：≤15 条硬规则（"条件→后果"）+ ≤10 条世界事实。
            不写来源书名，不保留原文专名。
            只输出一个 JSON 对象，无其他文字：
            {"arcs":[{"text":"[标签] 一句话事件链 ⇒ 结果","beats":[{"text":"≤40字的拍"}]}],
             "world":[{"kind":"RULE","text":"条件→后果"},{"kind":"FACT","text":"世界事实"}]}
            arcs 条数 4~8；每条 arcs 的 beats 条数 3~8；world 里 RULE≤15 且 FACT≤10；kind 只许 RULE 或 FACT。
            """;

    private final NovelMapper novelMapper;
    private final SourceChapterMapper sourceChapterMapper;
    private final AssetOutlineMapper outlineMapper;
    private final AssetBeatMapper beatMapper;
    private final AssetWorldMapper worldMapper;
    private final LlmJson llmJson;

    /** ②结构抽取：对源书全文调 LLM，落三张素材表。 */
    @Transactional
    public ExtractVO extract(long novelId) {
        NovelDO novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "书不存在: " + novelId);
        }
        List<SourceChapterDO> chapters = sourceChapterMapper.selectList(
                new LambdaQueryWrapper<SourceChapterDO>()
                        .eq(SourceChapterDO::getNovelId, novelId)
                        .orderByAsc(SourceChapterDO::getSeq));
        if (chapters.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本书没有章节，先走①取样入库");
        }
        String bookText = joinChapters(chapters);

        ExtractResult result = llmJson.ask(
                new LlmPort.ChatRequest(NODE, novelId, null,
                        List.of(LlmPort.Message.system(PROMPT),
                                LlmPort.Message.user("原文如下：\n" + bookText)),
                        0.2),
                DeriveExtractService::parseResult, 3);

        // 落弧（本地序号→插入 id，供拍挂父弧）
        Map<String, Long> arcIdByLocal = new HashMap<>();
        int beatCount = 0;
        for (ArcDraft a : result.arcs()) {
            AssetOutlineDO row = new AssetOutlineDO();
            row.setCode(nextCode(outlineMapper, "O"));
            row.setSrcNovelId(novelId);
            row.setExtractedText(a.text());
            outlineMapper.insert(row);
            arcIdByLocal.put(a.localId(), row.getId());
        }
        for (ArcDraft a : result.arcs()) {
            Long arcId = arcIdByLocal.get(a.localId());
            for (String beatText : a.beats()) {
                AssetBeatDO row = new AssetBeatDO();
                row.setCode(nextCode(beatMapper, "S"));
                row.setSrcNovelId(novelId);
                row.setExtractedText(beatText);
                row.setParentArcId(arcId);
                beatMapper.insert(row);
                beatCount++;
            }
        }
        for (WorldDraft w : result.world()) {
            AssetWorldDO row = new AssetWorldDO();
            row.setCode(nextCode(worldMapper, "W"));
            row.setSrcNovelId(novelId);
            row.setKind(w.kind());
            row.setExtractedText(w.text());
            worldMapper.insert(row);
        }
        log.info("②结构抽取：novelId={} 弧{} 拍{} 世界观{} 输入{}字",
                novelId, result.arcs().size(), beatCount, result.world().size(), bookText.length());
        return new ExtractVO(novelId, result.arcs().size(), beatCount, result.world().size(), bookText.length());
    }

    /** 拼正文（带章题分隔），超出上限截尾。 */
    static String joinChapters(List<SourceChapterDO> chapters) {
        StringBuilder sb = new StringBuilder();
        for (SourceChapterDO c : chapters) {
            if (sb.length() > INPUT_MAX_CHARS) {
                break;
            }
            sb.append("第").append(c.getSeq()).append("章 ").append(c.getTitle()).append('\n')
              .append(c.getContent()).append("\n\n");
        }
        String text = sb.toString();
        return text.length() > INPUT_MAX_CHARS ? text.substring(0, INPUT_MAX_CHARS) : text;
    }

    // ---- 解析与校验（LlmJson.parse 回调；不合规抛 Bad → 喂回重试）----

    record ArcDraft(String localId, String text, List<String> beats) {
    }

    record WorldDraft(String kind, String text) {
    }

    record ExtractResult(List<ArcDraft> arcs, List<WorldDraft> world) {
    }

    static ExtractResult parseResult(JsonNode root) {
        JsonNode arcs = root.path("arcs");
        if (!arcs.isArray() || arcs.size() < 4 || arcs.size() > 8) {
            throw new LlmJson.Bad("arcs 必须是 4~8 条的数组，当前 " + arcs.size());
        }
        List<ArcDraft> arcOut = new ArrayList<>();
        for (int i = 0; i < arcs.size(); i++) {
            JsonNode a = arcs.get(i);
            String text = a.path("text").asText("").trim();
            if (text.isEmpty()) {
                throw new LlmJson.Bad("arcs[" + i + "] 缺 text");
            }
            JsonNode beats = a.path("beats");
            if (!beats.isArray() || beats.size() < 3 || beats.size() > 8) {
                throw new LlmJson.Bad("arcs[" + i + "].beats 必须 3~8 拍");
            }
            List<String> beatOut = new ArrayList<>();
            for (int j = 0; j < beats.size(); j++) {
                String bt = beats.get(j).path("text").asText("").trim();
                if (bt.isEmpty()) {
                    throw new LlmJson.Bad("beats[" + j + "] 缺 text");
                }
                beatOut.add(bt);
            }
            arcOut.add(new ArcDraft("a" + i, text, beatOut));
        }
        JsonNode world = root.path("world");
        if (!world.isArray() || world.isEmpty()) {
            throw new LlmJson.Bad("world 必须是非空数组");
        }
        List<WorldDraft> worldOut = new ArrayList<>();
        int rules = 0;
        int facts = 0;
        for (int i = 0; i < world.size(); i++) {
            JsonNode w = world.get(i);
            String kind = w.path("kind").asText("");
            String text = w.path("text").asText("").trim();
            if (!"RULE".equals(kind) && !"FACT".equals(kind)) {
                throw new LlmJson.Bad("world[" + i + "].kind 只许 RULE/FACT，当前 " + kind);
            }
            if (text.isEmpty()) {
                throw new LlmJson.Bad("world[" + i + "] 缺 text");
            }
            if ("RULE".equals(kind) && ++rules > 15) {
                throw new LlmJson.Bad("RULE 超过 15 条");
            }
            if ("FACT".equals(kind) && ++facts > 10) {
                throw new LlmJson.Bad("FACT 超过 10 条");
            }
            worldOut.add(new WorldDraft(kind, text));
        }
        return new ExtractResult(arcOut, worldOut);
    }

    // ---- 全局 code 分配：O-Pxx 递增（跨书唯一，先按前缀捞全池解析最大号再 +1）----

    static String nextCode(AssetOutlineMapper mapper, String prefix) {
        List<String> codes = mapper.selectList(new LambdaQueryWrapper<AssetOutlineDO>()
                        .likeRight(AssetOutlineDO::getCode, prefix + "-P"))
                .stream().map(AssetOutlineDO::getCode).toList();
        return nextCode(prefix, codes);
    }

    static String nextCode(AssetBeatMapper mapper, String prefix) {
        List<String> codes = mapper.selectList(new LambdaQueryWrapper<AssetBeatDO>()
                        .likeRight(AssetBeatDO::getCode, prefix + "-P"))
                .stream().map(AssetBeatDO::getCode).toList();
        return nextCode(prefix, codes);
    }

    static String nextCode(AssetWorldMapper mapper, String prefix) {
        List<String> codes = mapper.selectList(new LambdaQueryWrapper<AssetWorldDO>()
                        .likeRight(AssetWorldDO::getCode, prefix + "-P"))
                .stream().map(AssetWorldDO::getCode).toList();
        return nextCode(prefix, codes);
    }

    /** 解析 "O-P07" → 7，取池内最大 +1（池为空从 1 起）。逐条解析而非字典序——O-P99 字典序大于 O-P100。 */
    static String nextCode(String prefix, List<String> codes) {
        int max = 0;
        for (String c : codes) {
            if (c == null || !c.startsWith(prefix + "-P")) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(c.substring(prefix.length() + 2)));
            } catch (NumberFormatException ignored) {
                // 跳过异形编号
            }
        }
        return prefix + "-P" + String.format("%02d", max + 1);
    }
}
