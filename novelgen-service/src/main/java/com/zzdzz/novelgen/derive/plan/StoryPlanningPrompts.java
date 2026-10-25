package com.zzdzz.novelgen.derive.plan;

/** 故事规划 V2 的提示词。 */
public final class StoryPlanningPrompts {

    public static final String NODE_SCALE = "derive_plan";
    public static final String NODE_SPINE = "derive_plan";
    public static final String NODE_VOLUMES = "derive_plan";
    public static final String NODE_CHARACTERS = "derive_plan";
    public static final String NODE_ROUTE = "derive_plan";

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
            只输出 JSON 对象：
            {"storyScale":"SHORT|MEDIUM|LONG","volumeCount":4,"chapterCount":48,
             "chapterAvg":2800,"scopeDecision":"SINGLE_VOLUME|MULTI_VOLUME|NEED_CONFIRMATION",
             "scopeEvidence":["支持该判断的用户原文"],"reason":"≤60字理由"}
            """;

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
            输入含卷合同、故事脊柱、创作框架；若带“上轮立项闸减法指令”，它是最高优先级，角色职能和关系必须与指令后的唯一真相一致。
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

    public static final String ROUTE = """
            你在为当前卷做章节功能槽与无指向拍路由，不写正文。
            输入含卷合同、角色计划、线程账、剩余无指向拍和本卷章功能模板；若带“上轮立项闸减法指令”，它是最高优先级，禁止恢复指令要求删除的案情或关系线。
            要求：
            1. chapters 必须覆盖本卷全部章号，每章给功能目标、主案推进和必需 beatRole；
            2. assignments 和 skipped 合起来必须恰好覆盖输入的全部剩余拍 seq，每拍只能出现一次；forcedSkips 中的 seq 禁止出现在 assignments，系统会自动写入 skipped；
            3. 每章至少分配1拍；具体拍数按剧情需要安排，不再限制单章上限；无关拍进 skipped 并写非空 reason；
            4. focusCharacters 只能使用输入角色 key；threadRefs 只能使用输入线程 key；
            5. 结构与功能缺失时可给 syntheticSlots，系统会生成 SYNTHETIC 补拍；syntheticSlots 与 assignments 共用 focusCharacters/threadRefs/dependsOn/payoffTarget/appearanceMode/requiresKnowledge/writesKnowledge/requiresState/changesState，建立前置知识的补拍必须写 writesKnowledge；
            6. 每卷末章必须有 LOCAL_CLOSURE；非末卷还要有 NEXT_HOOK，最终卷用 FINAL_CLOSURE；
            7. appearanceMode 只能取 LIVE/MEMORY/DREAM/CORPSE/RELIC/TESTIMONY/LETTER/RECORDING；
            8. storyLayer、beatRole 只能取合同枚举值；
            9. requiresKnowledge 只写正文中角色可感知的事实/现象，不填 GM-/REL-/VC-/OBJ- 线程 key；
               writesKnowledge 同理只写事实文本。
            10. 知识因果必须按人物成立：一条事实首次被 writesKnowledge 时，只有该拍 focusCharacters
                中的角色算“已知”；后续任何角色 requiresKnowledge 前，必须有一场同时聚焦该角色、
                且写入该事实的前置拍。若缺这个前置拍，补 syntheticSlots 或在现有拍中调整
                focusCharacters，不能默认全员共享知识。输入若带「返工问题」，必须逐条消除并重新输出
                完整本卷路由，禁止原样重复上一版。
            只输出 JSON 对象：
            {"volumeNo":1,"chapters":[{"chapterNo":1,"chapterFunction":"HOOK",
              "localCaseGoal":"…","mainMysteryAdvance":"…","characterGoal":"…",
              "relationshipGoal":"…","requiredBeatRoles":["HOOK"],
              "forbiddenAppearances":[],"requiredStateBefore":{},"requiredStateAfter":{}}],
             "assignments":[{"seq":1,"chapterNo":1,"storyLayer":"LOCAL_CASE",
               "beatRole":"HOOK","focusCharacters":["C1"],"threadRefs":["GM-CLUE-01"],
               "dependsOn":[],"payoffTarget":"VC1","appearanceMode":"LIVE",
               "requiresKnowledge":[],"writesKnowledge":["门底有2厘米文件缝"],
               "requiresState":{},"changesState":{}}],
             "syntheticSlots":[],"skipped":[{"seq":2,"reason":"…"}]}
            """;
}
