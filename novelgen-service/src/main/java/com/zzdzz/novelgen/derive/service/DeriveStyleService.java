package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.SourceChapterMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.SourceChapterDO;
import com.zzdzz.novelgen.model.vo.derive.StyleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ④d 文风抽取（v3.6 方案B 可选步，独立端点）。
 *
 * <p>读源书正文（source_chapter，≤{@value #INPUT_MAX_CHARS} 字）→ LLM 产出 style_profile 画像
 * → 覆盖式幂等落 novels.story_settings.style_profile（jsonb 留缝，不加表）。
 * 不跑此步则无文风卡；⑥a 建成品书时会把第一本抽中弧所在源书的卡复制过去（DerivePlanService），
 * ⑦ 无 param_style 时注入、⑧ voice 项作对照基准。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveStyleService {

    /** 台账节点名（llm_call_log.node / llm_node_config 路由键）。 */
    public static final String NODE_STYLE = "derive_style";

    /** 喂给 LLM 的正文上限：文风画像看肌理不吃全书，超限截头部。 */
    static final int INPUT_MAX_CHARS = 30_000;

    static final String STYLE_PROMPT = """
            你在为一本小说做文风画像。输入：书名与正文节选。只依据给定正文，禁止发明。
            输出 JSON 对象（只输出 JSON）：
            {"v":1,
             "sentence":"句式画像：短句为主/长短缠绕/欧化长定语…（≤80字）",
             "dialogue":"对话密度与质感，含示例腔调（≤80字）",
             "narration":"叙述/环境/抒情配比（≤60字）",
             "lexicon":{"idiom":"成语频率档（高/中/低/无）","wenyan":"文言腔档（高/中/低/无）","colloquial":"口语度档（高/中/低）"},
             "imagery":["该书惯用的感官与器物意象，3~8条"],
             "pov":"视角与内视角习惯（≤60字）",
             "density":"叙事密度画像：每千字推进量观感（≤60字）",
             "no_go":["该文风的禁忌清单，3~6条，写成“禁止…”句式"]}
            """;

    private final NovelMapper novelMapper;
    private final SourceChapterMapper sourceChapterMapper;
    private final LlmJson llmJson;

    /** ④d 文风抽取：覆盖式幂等（重跑整键覆盖）。 */
    public StyleVO extract(long novelId) {
        NovelDO novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "书不存在: " + novelId);
        }
        List<SourceChapterDO> chapters = sourceChapterMapper.selectList(
                new LambdaQueryWrapper<SourceChapterDO>()
                        .eq(SourceChapterDO::getNovelId, novelId)
                        .orderByAsc(SourceChapterDO::getSeq));
        if (chapters.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "本书无源书正文（source_chapter 空）——④d 只能对导入源书跑");
        }
        String bookText = joinHead(chapters, INPUT_MAX_CHARS);

        JsonNode profile = llmJson.ask(
                new LlmPort.ChatRequest(NODE_STYLE, novelId, null,
                        List.of(LlmPort.Message.system(STYLE_PROMPT),
                                LlmPort.Message.user("《" + novel.getTitle() + "》正文节选如下：\n" + bookText)),
                        0.3),
                DeriveStyleService::parseStyle, 3);

        novelMapper.updateStyleProfile(novelId, profile.toString());
        log.info("④d文风：novelId={} {}字 → style_profile 已落库（no_go={}条 imagery={}条）",
                novelId, bookText.length(),
                profile.path("no_go").size(), profile.path("imagery").size());
        return new StyleVO(novelId, bookText.length(), profile);
    }

    /** 按章拼接取头部 ≤limit 字（整章边界截断，不劈开半章）。 */
    static String joinHead(List<SourceChapterDO> chapters, int limit) {
        StringBuilder sb = new StringBuilder();
        for (SourceChapterDO c : chapters) {
            if (c.getContent() == null || c.getContent().isBlank()) {
                continue;
            }
            if (sb.length() >= limit) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(c.getContent());
        }
        return sb.length() <= limit ? sb.toString() : sb.substring(0, limit);
    }

    /** 结构校验（不过喂回重试）：核心键非空 + lexicon 三档 + imagery/no_go 非空数组。 */
    static JsonNode parseStyle(JsonNode root) {
        for (String k : List.of("sentence", "dialogue", "narration", "pov", "density")) {
            if (root.path(k).asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("style_profile 缺 " + k);
            }
        }
        JsonNode lex = root.path("lexicon");
        for (String k : List.of("idiom", "wenyan", "colloquial")) {
            if (lex.path(k).asText("").trim().isEmpty()) {
                throw new LlmJson.Bad("style_profile.lexicon 缺 " + k);
            }
        }
        if (!root.path("imagery").isArray() || root.path("imagery").isEmpty()) {
            throw new LlmJson.Bad("style_profile.imagery 须为非空数组（3~8条惯用意象）");
        }
        if (!root.path("no_go").isArray() || root.path("no_go").isEmpty()) {
            throw new LlmJson.Bad("style_profile.no_go 须为非空数组（3~6条禁忌）");
        }
        return root;
    }
}
