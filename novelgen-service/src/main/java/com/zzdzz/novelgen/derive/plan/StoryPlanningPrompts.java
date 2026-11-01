package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.llm.PromptCatalog;
import com.zzdzz.novelgen.llm.contract.JsonSchemaSupport;
import java.util.Arrays;
import java.util.Objects;
/** 故事规划 V2 的提示词。 */
public final class StoryPlanningPrompts {

    public static final String NODE_SCALE = "derive_plan";
    public static final String NODE_SPINE = "derive_plan";
    public static final String NODE_VOLUMES = "derive_plan";
    public static final String NODE_CHARACTERS = "derive_plan";
    public static final String NODE_ROUTE = "derive_plan";
    public static final String NODE_CONTRACT = "derive_contract";

    private StoryPlanningPrompts() {
    }

    public static final String SCALE = """
            你在为一部小说判断篇幅规模与卷级范围，不写章纲和正文。
            输入含创作框架、字数边界、类别、节奏、形态、结局、主线、拍数。
            规则：
            1. 用户显式写出的卷号或卷数是硬约束，必须原样遵守。
            2. 用户只描述一卷且没有后续卷目标时，默认单卷。
            3. “可以多卷”但没有实际卷级事件时，仍为单卷。
            4. 字数区间和拍数只做可行性边界，不得推翻创作框架。
            5. 无法安全判断单卷/多卷时，scopeDecision=NEED_CONFIRMATION。
            6. chapterCount 是规模建议值，必须≥1；chapterAvg 必须>0。章数可以高于原始拍数，结构缺口由后续 syntheticSlots 补齐。
            7. scopeDecision=SINGLE_VOLUME 时 volumeCount=1；
               scopeDecision=MULTI_VOLUME 时 volumeCount≥2。
            8. scopeEvidence 必须是纯字符串数组，每项写成“字段=值”（例如 产品形态=SHORT_STANDALONE）；
               禁止输出对象、键值对 JSON 或带冒号的数组元素。
            只输出 JSON 对象：
            {"storyScale":"SHORT|MEDIUM|LONG","volumeCount":4,"chapterCount":48,
             "chapterAvg":2800,"scopeDecision":"SINGLE_VOLUME|MULTI_VOLUME|NEED_CONFIRMATION",
             "scopeEvidence":["产品形态=SHORT_STANDALONE","规模判断口径=单卷框架"],"reason":"≤60字理由"}
            """;

    /**
     * 旧 SPINE 提示词：SPINE 现已由冻结合同确定性重建（{@code spineFromContract}），不再调 LLM。
     * 保留常量只为兼容历史 run 与提示词目录引用，新代码禁止再使用。
     */
    @Deprecated
    public static final String SPINE = """
            你在为一部多卷小说设计故事脊柱，不写卷纲、章纲或正文。
            输入含规模、创作框架、主线、类别、结局形状；若带“上轮立项闸减法指令”，它是最高优先级，必须删除与之冲突的案情、角色和手法。
            要求：
            1. mainMystery 是贯穿全书的唯一背景大事件；
            2. finalTruth 必须是可验证的完整真相，不能只写“更大阴谋”；
            3. finalChoice 是主角在最终卷必须作出的不可替代选择；
            4. truthLadder 必须覆盖 volumeNo=1..volumeCount，每卷恰好一条；
            5. 每卷 reveals 必须增加不可逆的大主线信息；forbidden 写明本卷仍不可揭示的内容；
            6. globalThreads 只放跨卷必须追踪的 CLUE/OBJECT 线程，threadKey 全书唯一；
            7. 每条 globalThread 必须有 payoffVolume，且不大于 volumeCount。
            只输出 JSON 对象：
            {"mainMystery":"…","finalTruth":"…","finalChoice":"…",
             "truthLadder":[{"volumeNo":1,"reveals":["…"],"forbidden":["…"],
                             "evidenceThreads":["GM-CLUE-01"]}],
             "globalThreads":[{"threadKey":"GM-CLUE-01","kind":"CLUE",
                               "title":"…","openedVolume":1,"payoffVolume":4,
                               "ownerCharacterKey":"C1",
                               "contract":{"plantedChapter":3,"payoffChapter":43}}]}
            """;

    public static final String VOLUMES = """
            你在把故事脊柱拆成卷合同，不写章纲或正文。
            输入含规模、故事脊柱、创作框架和大主线；若带“上轮立项闸减法指令”，它是最高优先级，卷合同不得保留指令要求删除的内容。
            要求：
            1. volumes 数量必须等于 volumeCount，卷号从1连续；
            2. fromChapter/toChapter 连续、不重叠，覆盖1..chapterCount；
            3. 每卷必须有 localQuestion/falseAnswer/trueAnswer/mainMysteryAdvance；
            4. 每卷必须有 localClosureProof；非末卷 nextHook 非空，最终卷 nextHook 为空；
            5. 最终卷 globalRole=FINAL，其余 LOCAL；
            6. focusCharacters 只能引用稳定角色 key 或“未定”，不得凭空增加卷。
            7. fromChapter/toChapter 必须与输入 volumeRanges 完全一致，不得自行改分卷边界；
               requiredThreads 里每条线索的埋设/兑现章必须在合同给出的章号范围内。
            只输出 JSON 对象：
            {"volumes":[{"volumeNo":1,"fromChapter":1,"toChapter":12,"arc":"…",
              "localQuestion":"…","falseAnswer":"…","trueAnswer":"…",
              "mainMysteryAdvance":"…","focusCharacters":["C1"],
              "relationshipChanges":["REL-C1-C2"],"requiredThreads":["GM-CLUE-01"],
              "forbiddenReveals":["…"],"localClosureProof":"…",
              "nextHook":"…","globalRole":"LOCAL"}]}
            """;

    public static final String CHARACTERS = """
            你在为一部多卷小说建立角色叙事计划，不写正文。
            输入含卷合同、故事脊柱、创作框架、cast role/persona/brief；若带“上轮立项闸减法指令”，它是最高优先级，角色职能、arcBefore/arcAfter、highlightChoice 和关系必须与人物表 persona 及唯一真相一致，禁止生成与人物表 role 互斥的角色状态。
            要求：
            1. characterKey 使用 C1..Cn 且全书唯一；displayNameHint 可空；
            2. function 只能取 DETECTIVE/ACCESS/KNOWLEDGE/SOCIAL/ACTION/CHALLENGE/SUPPORT/OPPOSITION；
            3. uniqueContribution 必须是剧情不可替代的条件；
            4. spotlightVolume/conflictVolume/payoffVolume 必须落在已有卷号；
            5. 每个高光角色必须有 highlightChoice 和 highlightCost；
            6. conflictTargets 必须引用其他 characterKey；
            7. arcBefore/arcAfter/payoffProof 不得为空。
            只输出 JSON 对象：
            {"characters":[{"characterKey":"C1","displayNameHint":"…",
              "function":"DETECTIVE","spotlightVolume":4,"conflictVolume":3,"payoffVolume":4,
              "uniqueContribution":"…","want":"…","flaw":"…",
              "conflictTargets":["C2"],"functionalConflict":"…",
              "highlightChoice":"…","highlightCost":"…",
              "relationshipChanges":["REL-C1-C2"],
              "arcBefore":"…","arcAfter":"…","payoffProof":"…"}]}
            """;

    /** ROUTE 的带 token 模板：目录与代码共用同一份文本。 */
    public static final String ROUTE_TEMPLATE =
            Objects.requireNonNull(PromptCatalog.contentOf(NODE_ROUTE, "system"),
                    "PromptCatalog 缺少 derive_plan/system ROUTE 模板");

    /** ROUTE 渲染后的 system prompt：token 已解析；输出契约必须与 DeriveBeatRouteService.parseRoute 一致。 */
    public static final String ROUTE = renderRouteSystemPrompt();

    /**
     * 渲染 ROUTE system prompt。
     *
     * @return 解析全部枚举 token 后的提示词；不追加任何第二套输出 schema
     */
    public static String renderRouteSystemPrompt() {
        return ROUTE_TEMPLATE
                .replace("{{BEAT_ROLES}}", enumBulletList(PlanContracts.BeatRole.values()))
                .replace("{{STORY_LAYERS}}", enumBulletList(PlanContracts.StoryLayer.values()))
                .replace("{{APPEARANCE_MODES}}", enumBulletList(PlanContracts.AppearanceMode.values()))
                .replace("{{STATE_CHANGE_KINDS}}", enumBulletList(PlanContracts.StateChangeKind.values()));
    }

    private static String enumBulletList(Enum<?>[] values) {
        return JsonSchemaSupport.bulletList(
                Arrays.stream(values).map(Enum::name).toList());
    }
    public static final String CONTRACT = """
            你在为一部小说冻结唯一故事合同，不写卷纲、章纲或正文。
            输入含：创作框架、类别、结局形状、产品形态、焊接主线、scaffold facts、章数/卷数、maxNamedCharacters。
            你的输出一旦冻结，后续所有规划、⑥b、P6、正文都必须服从它，任何下游节点都不得改写。
            要求：
            1. 恰好 3 名有名角色；cast.key 固定 C1/C2/C3；主角为 C1；OPPOSITION 必须是其中一名；
               role 只能取 DETECTIVE/ACCESS/KNOWLEDGE/SOCIAL/ACTION/CHALLENGE/SUPPORT/OPPOSITION，
               identity 写一句话身份（≤20字），name 是全书唯一合法人名来源（2~3 字，不得用禁用名单里的名字）；
            2. culpritKey 必须是 cast.key；culpritName 必须等于该 cast.name；
            3. 只允许一个主案；motive/method/finalTruth 必须能解释全部关键事实；
            4. revealChapter 必须在 1..chapterCount；clues 至少 3 条，plantedChapter/payoffChapter
               均在 1..chapterCount 且 payoffChapter > plantedChapter；clueKey 形如 GM-CLUE-01 且互不重复；
            5. forbidden 必须是 JSON 字符串数组，禁止写成字符串化的 JSON；
            6. 多卷时必须给每卷 volumeTruth，卷号连续覆盖 1..volumeCount；单卷也给 volumeNo=1 一条；
            7. 自洽自检（冻结前必须逐条核对）：
               - method 里“谁做了什么”必须能解释每条 clue 的现象；不得出现“某人抄录”与“笔迹不是他”并存；
               - finalTruth 对方法的描述必须与 method 同义，不得一个说“抄录”、一个说“出自某人手笔”；
               - 若 clue 依赖某人的笔迹/字迹，method 必须写清该笔迹为何偏离其平日（例如失控状态下誊抄），
                 禁止留下“抄录/代笔”二义；
            8. revealChapter 必须至少等于一条 clue 的 payoffChapter：揭示章必须有一件线索当场兑现，
               否则结局三要素会悬空；
            9. 额外输出两个短标签（4~20 字，禁止空、禁止只写“复仇/阴谋”这类空词）：
               motiveTag：motive 的核心短语（例：夺凭证控据点）；
               methodTag：method 的核心短语（例：符号引诱+催眠控心）；
               这两个标签会被下游章纲逐字引用并做确定性校验。
            10. 不得引入焊接主线之外的新主案、第二起案件、死亡或超自然解释，除非创作框架明确允许；
            11. 非推理/悬疑题材同样要填满 truth：culprit 写最终揭示或对决的关键对立角色，
               motive/method 写其动机与关键手段，clues 写可观察的伏笔线索，revealChapter 写答案揭晓章；
            12. threads 至少覆盖每条线索（threadKey 与 clueKey 同名），kind 取 CLUE/RELATIONSHIP/OBJECT，
               openedVolume/payoffVolume 在 1..volumeCount 且 payoffVolume >= openedVolume。
            只输出 JSON 对象：
            {"cast":[{"key":"C1","name":"…","role":"DETECTIVE","gender":"…","identity":"…"},
                      {"key":"C2","name":"…","role":"ACCESS","gender":"…","identity":"…"},
                      {"key":"C3","name":"…","role":"OPPOSITION","gender":"…","identity":"…"}],
             "truth":{"culpritKey":"C3","culpritName":"…","motive":"…","method":"…",
                      "finalTruth":"…","finalChoice":"…","revealChapter":12,
                      "motiveTag":"…","methodTag":"…"},
             "clues":[{"clueKey":"GM-CLUE-01","clue":"可观测现象","plantedChapter":3,"payoffChapter":11,"ownerKey":"C1"}],
             "threads":[{"threadKey":"GM-CLUE-01","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"}],
             "forbidden":["…"],
             "volumeTruth":[{"volumeNo":1,"reveal":"…","forbidden":["…"]}]}
            """;
}
