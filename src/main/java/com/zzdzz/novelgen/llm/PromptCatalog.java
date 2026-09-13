package com.zzdzz.novelgen.llm;

import java.util.List;

/**
 * 提示词注册表：各 LLM 节点 system/user 模板的集中目录，启动时由 PromptTemplateService 同步落库。
 * exact=true 表示与代码中的格式模板逐字一致（%s/%d 为运行时占位），管线运行时库值优先、代码块为回退；
 * exact=false 为运行时拼接件的骨架快照（仅浏览，不接库读取）。
 * 代码模板改动后：未人工定制的行会在下次启动自动对齐（version+1）；custom 行不受影响，可前端一键重置。
 */
public final class PromptCatalog {

    public record TemplateDef(String node, String phase, String title, boolean exact, String content) {}

    private PromptCatalog() {}

    public static final List<TemplateDef> ALL = List.of(

        // ===== 章纲 =====
        new TemplateDef(LlmNode.OUTLINE, "user", "AI 章纲生成（场景拆解）", true, """
                任务：为第 %d 章《%s》编写场景级章纲。
                本章卷纲目标：%s
                章末钩子类型：%s
                本章距上一章的时间跨度：%s（场景与对白须体现该推进，不可凭空另设时间线）
                本章涉及的守则：%s
                伏笔任务：%s
                全章字数预算：%d–%d 字，必须拆成 2-3 个场景，每个场景 800–1200 字。

                【世界观（必须遵守，不得发明矛盾设定）】
                %s

                %s

                【前情摘要】
                %s

                【上一章事件后果（第一场景必须与之对接：兑现、交代或明确推进，禁止无视另起炉灶）】
                %s

                【上一章结尾原文（衔接其节奏）】
                %s

                只输出 JSON，格式：
                {"scenes":[{"no":1,"goal":"本场景目标","present":["出场人物"],"must_reveal":["必须让读者知道的信息"],"must_not":["禁止出现的内容"],"words":900}]}
                """),

        // ===== 场景生成 =====
        new TemplateDef(LlmNode.SCENE_DRAFT, "system", "场景写作系统提示（风格包红线；未含量化画像时叠加默认画像）", false, """

                【量化风格画像（按此密度写，门禁按同口径验收）】
                - 对话密：每千字约 19 行「」对话——推动情节靠人物说话，不靠叙述转述。
                - 一行一拍：平均每行 15-22 字。
                - 破折号——每千字 2-4 个（同位语补充设定）；省略号……每千字 4-6 个（拖长的思绪）。
                - 对话行句末 85% 以上不加标点（问句可留？）。例：写「走吧」，不要写「走吧。」；旁白行才用句号。
                - 阿拉伯数字只用于钱（"时薪18""31块"），每千字不超过 12 个；
                  时间写中文（凌晨两点，不写凌晨2点）；守则条文序号用中文（第一条，不写第1条）。
                - 顿号每千字不超过 1 个；感叹号每千字不超过 2 个。
                """),

        new TemplateDef(LlmNode.SCENE_DRAFT, "user", "场景正文生成", true, """
                任务：写第 %d 章场景 %d。
                本章目标：%s
                本场景目标：%s
                出场人物：%s
                必须让读者知道：%s
                禁止出现：%s
                本场景字数预算：约 %d 字（±15%%），只输出正文。

                【信息密度红线（逐条硬性执行，与开篇红线同等效力）】
                - 情节推进靠人物说话：本章大部分节拍用对白承载，叙述只做对白之间的呼吸——大段描写是本书第一大忌。
                - 每一行必须干一件活：推进事件、揭示新信息、或改变威胁与关系。写完自问「删掉这行读者会少知道什么」，答不出来就删。
                - 一个微动作（点头/起身/放杯/搁笔）最多一行；禁止连续两行写同一对象；禁止给动作写人物志。
                - 比喻（像/仿佛/如同）每千字不超过 %s 个；「不是……是……」式修辞每场景最多 2 次。
                - 观察只许作为行动的前奏——每个观察必须引出下一个动作或决定。

                %s

                【世界观（必须遵守）】
                %s

                %s

                【伏笔任务】
                %s

                【前情摘要】
                %s

                【相关前史（语义检索召回，带出处；与本章情节相关才用，禁止硬凑）】
                %s

                【世界状态（上一章结束时，必须遵守——物品归属与位置不得凭空变化）】
                %s

                【上一章事件后果（本章开场必须与之对接：兑现、交代或明确推进，禁止无视另起炉灶）】
                %s

                【上一场景已写内容（紧接其后继续写；禁止复述其中任何句子——你的第一行必须是全新的句子；禁止重复情节与时间点）】
                %s
                """),

        new TemplateDef(LlmNode.SCENE_REVISE, "system", "场景重写系统提示（复用场景包 system）", false,
                "同 scene_draft/system：复用场景包 system（风格包红线 + 量化风格画像）。"),

        new TemplateDef(LlmNode.SCENE_REVISE, "user", "场景门禁重写", false, """
                {场景包 user（同 scene_draft/user 组装结果）}

                【你上一稿】
                {draft：上一稿正文}

                【门禁意见（只改被点名的问题，保持其余原样）】
                {gateFeedback：门禁未过项与原因}

                只输出修订后的完整正文。
                """),

        // ===== 章级机械门禁修订 =====
        new TemplateDef(LlmNode.CHAPTER_REVISE, "system", "章级门禁修订系统提示", true,
                "你是执行门禁修订的网文编辑，只做被点名的最小修改。"),

        new TemplateDef(LlmNode.CHAPTER_REVISE, "user", "章级门禁修订", true, """
                任务：修订第 %d 章全文。门禁检测出以下问题：
                %s
                要求：只针对被点名的问题做最小修改（例如破折号超标：把「——」改写为逗号、句号、拆句或直接删除）；
                除被点名的指标外，其余风格特征必须原样保留——破折号「——」与省略号「……」的数量不得增加，分行节奏不得重排；
                严禁改动情节、人物与对话内容；%s。
                直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """),

        // ===== 读者评审与重写 =====
        new TemplateDef(LlmNode.READER_REVIEW, "system", "读者评审五问（%s=注水率 blocker 阈值，tuning: reader_fat_ratio_block）", true, """
                你是一个没耐心的网文读者，刷手机时点开了这一章。你只关心「想不想继续读」，只回答下列问题：
                1. hook 前 3 行：会不会继续往下读？（环境/氛围/抒情式开场、或与上章结尾接不上=不会）
                2. stakes 这场戏：谁想要什么？什么在阻止？（说不出来=没有戏剧张力）
                3. continuity 读完前 10 行，能否定位上一章结束时的情境（时间/地点/在场人物）？（定位不到=衔接断裂）
                4. fat 与剧情无关、删掉后读者不会少知道任何事的纯装饰描写（给微动作写人物志、连篇比喻、静态观察），占比大约多少？
                5. consequence 上下文给出了【上一章事件后果】（上一章的目标、章末钩子与实际收束）。本章是否与之对接——给出兑现、交代或明确推进？（完全无视另起炉灶=fail）
                只输出 JSON：
                {"verdict":"pass|blocker","hook":"pass|fail","stakes":"pass|fail","continuity":"pass|fail","consequence":"pass|fail","fat_ratio":0.4,"skip_quotes":["可整段删除的原句"],"issues":["具体问题（引用原句）"]}
                规则：
                - 引用原文一律用「」；字符串值内部禁止英文双引号。
                - hook/stakes/continuity/consequence 任一 fail，或 fat_ratio 大于 %s → verdict=blocker；否则 pass。
                - 你只管「想不想往下读」，错别字与设定连续性是另一位审校的事，不要报。
                - 不要输出思考过程，只输出 JSON。
                """),

        new TemplateDef(LlmNode.READER_REVIEW, "user", "读者评审输入", false, """
                【上一章结尾（衔接定位基准）】
                {prevTail}

                【上一章事件后果】
                {prevBrief（第一章时提示 consequence 直接 pass）}

                【本章目标】{goal}

                【第 {chapterNo} 章全文（评审对象）】
                {fullText}

                只输出 JSON。
                """),

        new TemplateDef(LlmNode.READER_FIX, "system", "读者重写系统提示", true,
                "你是网文编辑，任务是让这一章「每一行都值得读」：删注水、保情节、补张力。"),

        new TemplateDef(LlmNode.READER_FIX, "user", "读者重写（弃书理由驱动的整章重写）", true, """
                任务：修订第 %d 章全文。没耐心的网文读者给出以下弃书理由：
                %s
                要求：情节、信息与对白立场全部保留；删掉全部纯装饰描写与重复观察；推动情节的对白可以增加；
                分行节奏与风格特征保持本书原貌；直接输出修订后的完整正文，不要输出思考过程。
                本章篇幅约束：%s。

                【第 %d 章全文（在此版本上修改）】
                %s
                """),

        // ===== AI 语义审校 =====
        new TemplateDef(LlmNode.AI_REVIEW, "system", "AI 语义审校四查", true, """
                你是资深网文审校编辑，在机械门禁之后做语义审校。只查以下四类问题：
                1. continuity 连续性：与给定上下文（世界设定、人物卡、近章事实账、上一章结尾）矛盾——时间线、称呼、物件、地点、人物状态。
                2. logic 逻辑硬伤：情节自相矛盾、前因后果断裂。
                3. typo 错别字/用词错误：明显的错字、漏字、用词不当。
                4. format 格式：章题混入正文、markdown 残留、阿拉伯数字。
                只输出 JSON：
                {"verdict":"pass|minor|blocker","summary":"一句话总评","issues":[{"type":"continuity|logic|typo|format","severity":"minor|blocker","quote":"原句","explanation":"问题说明","suggestion":"修改建议"}]}
                规则：
                - 引用原文一律用「」；字符串值内部禁止英文双引号。
                - 存在必须改的硬伤（时间线矛盾、称呼错、错字）才判 blocker；只有不破坏阅读的瑕疵判 minor；无问题判 pass。
                - 宁可漏报不可误报：没有把握的不要报，不提风格意见。
                - 不要输出思考过程，只输出 JSON。
                """),

        new TemplateDef(LlmNode.AI_REVIEW, "user", "AI 审校输入", false, """
                【世界设定与大纲】{world}

                【人物卡】{characters}

                【世界状态（上一章结束时）】{worldState（无则「（无）」）}

                【近章事实账】
                {digests：近 3 条以 --- 分隔（无则「（无）」）}

                【上一章结尾】{prevTail}

                【第 {chapterNo} 章全文（审校对象）】
                {fullText}

                审校以上全文，只输出 JSON。
                """),

        new TemplateDef(LlmNode.AI_REVIEW_REVISE, "system", "审校修订系统提示", true,
                "你是执行审校修订的网文编辑，只做被点名的最小修改。"),

        new TemplateDef(LlmNode.AI_REVIEW_REVISE, "user", "审校修订（硬伤最小修复）", true, """
                任务：修订第 %d 章全文。语义审校发现以下必须修复的问题：
                %s
                要求：只修被点名的问题（错字改字、矛盾句最小改写），严禁改动情节走向与分行节奏，总字数变化控制在 ±10%% 内。
                直接输出修订后的完整正文，不要输出思考过程。

                【第 %d 章全文（在此版本上修改）】
                %s
                """),

        // ===== digest 四产出 =====
        new TemplateDef(LlmNode.DIGEST, "system", "事实账提取（%s=世界状态 state 字段规格）", true, """
                你是事实账记录员。把章节压缩成供后续章节续写使用的事实账，只输出 JSON：
                {"summary_md":"300字以内的md：谁做了什么/信息揭示/情绪落点/章末钩子",
                 "facts":["一条一句的硬事实（人名、物件、承诺、时间线变化）"],
                 %s,
                 "new_threads":[{"name":"三到六字短名","content":"一句话：这条新长线是什么、为何值得跨章追踪"}]}
                字符串值内部禁止使用英文双引号，引用一律用「」。
                summary_md 不要包含任何标题行，直接从摘要正文开始。
                new_threads 只提议真正的长线（需要多章才能回收的谜、承诺、关系变化），本章内已解决的不提；
                与已有伏笔账本同义的不提；最多 2 条；没有就给空数组。
                """),

        new TemplateDef(LlmNode.DIGEST, "user", "digest 输入（时间锚点+伏笔账本+全文）", false, """
                〔运行时按序拼接，均有则拼〕
                【时间锚点】本章距上一章：{timeNote}（state.time 必须体现该推进）

                【已有伏笔账本（同义勿重复提议）】
                {code（status）content 逐条}

                【本章全文】
                {fullText}
                """),

        new TemplateDef(LlmNode.WORLD_STATE, "system", "世界状态快照（%s=state 字段规格）", true, """
                你是世界状态记录员。读完本章，输出本章结束时刻的结构化状态快照，只输出 JSON：
                {%s}
                规则：只记硬事实；人名用规范名；拿不准的不写；字符串值内部禁止英文双引号，引用一律用「」。
                """),

        // ===== 卷纲规划 =====
        new TemplateDef(LlmNode.VOLUME_PLAN, "system", "整卷规划系统提示", true,
                "你是网文主编，负责整卷卷纲规划。只输出合法 JSON，不要任何解释或 markdown 代码块。"
                        + "字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.VOLUME_PLAN, "user", "整卷规划（戏剧四件套 + 伏笔排期）", true, """
                任务：规划第 %d 卷，从第 %d 章开始，%s。

                规划规则：
                1. brief 为 150-300 字卷简报，必须写清四个决策：本卷核心悬念与谜底展开节奏（人物身份/动机类问题的答案在本卷如何推进）、卷终点钩子（终章留给下一卷的最大悬念）、伏笔取舍（哪些回收、哪些继续悬置及理由）、节奏曲线（紧张章与舒缓章如何分布）。
                2. chapters.no 从 %d 开始连续编号；title 不超过 12 字；goal 100-200 字且按戏剧结构写四件套——欲望（本章谁想要什么）、阻碍（什么在阻止）、转折（章内如何升级或翻转）、情绪落点，供下游场景拆解器使用；hook 为一句话章末钩子；time_note 为本章距上一章的故事时间跨度（如「紧接」「次日清晨」「三天后」，不得与时间线矛盾）。
                3. foreshadows 只列本章要「埋设」或「回收」的伏笔：账本中 proposed/planned 的编码被引用即排期埋设，planted 的被引用即安排回收（action=recover）；账本里没有的新伏笔省略 code、必须给 content（一句话）且 action=plant，将自动建账；已 recovered 的不要引用（旧线呼应写进 goal 即可）；与本章无关的不要列。
                4. budget_min/budget_max 为单章字数预算，参考往卷实际水平 2800-4000。
                5. 卷尾必须留下强钩子；不得与已有卷纲重复桥段。
                6. 若上下文给出【上卷复盘要点】，必须在 brief 决策与章节安排中做出回应：点名的悬置伏笔优先安排兑现（引用编码即排期）或给出明确悬置理由；漂移项须有对应修正安排。

                只输出 JSON，格式：
                {"arc":"卷名（8字内）","brief":"…","chapters":[{"no":%d,"title":"…","goal":"…","hook":"…","time_note":"…","foreshadows":[{"code":"F4","action":"recover","content":""},{"code":"","action":"plant","content":"新伏笔一句话"}],"budget_min":2400,"budget_max":3400}]}
                字符串值内部禁止英文双引号，引用一律用「」。

                %s
                """),

        new TemplateDef(LlmNode.VOLUME_PLAN_REVIEW, "system", "卷纲审校系统提示", true,
                "你是网文规划审校员，在卷纲落库前把关。只输出合法 JSON。"
                        + "字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.VOLUME_PLAN_REVIEW, "user", "卷纲审校（连续性/重复/伏笔/节奏四查）", true, """
                【待审卷纲】
                %s
                【对照材料（人物设定卡 + 账本）】
                %s
                %s
                审校清单：① 连续性——是否与世界观/人物卡/世界状态/事实账矛盾（人物已死复活、物品凭空转移、时间倒流、凭空发明人物卡与账本中不存在的人名）；
                ② 重复——卷内相邻章目标是否雷同、是否与往卷炒冷饭；③ 伏笔——planted 未回收项是否被安排回收或给出悬置理由、proposed 取舍是否合理；
                ④ 节奏——张弛是否有曲线、卷尾钩子是否成立。
                只输出 JSON：{"verdict":"PASS"或"BLOCKER","issues":["问题（指明章号）"]}
                存在必须修复的硬伤才 BLOCKER；风格偏好类意见写进 issues 但给 PASS。
                """),

        // ===== 卷级复盘 =====
        new TemplateDef(LlmNode.VOLUME_REVIEW, "system", "卷级复盘系统提示", true,
                "你是资深网文责编，负责卷级复盘。只输出合法 JSON，"
                        + "字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.VOLUME_REVIEW, "user", "卷级复盘（对照卷纲意图找漂移）", true, """
                任务：复盘第 %d 卷（第 %d-%d 章）。你是资深网文责编，对照卷纲意图与实际成稿找漂移。

                【卷纲行 vs 实际】
                %s
                【各章事实账（实际发生了什么）】
                %s
                【世界状态：卷首】
                %s
                【世界状态：卷末】
                %s
                【机械对账（确定性结果，直接采信）】
                %s

                只输出 JSON：
                {"overall":"pass|drift|critical","summary":"300字内总评：主线推进/人物弧光/卷尾钩子兑现度",
                 "drifts":[{"type":"plot|foreshadow|character|world|pacing","severity":"minor|major",
                   "where":"第N章或全卷","issue":"漂移描述（对照卷纲意图）","suggestion":"怎么改"}],
                 "highlights":["做得好的点"],"next_volume":"下一卷建议（150字内）"}
                规则：
                - 机械对账已给出的伏笔/字数结论不要重复报，只在其揭示的模式上展开叙事层分析。
                - 漂移 = 实际走向偏离卷纲意图或前后矛盾；没有把握的不要报；字符串值内部禁止英文双引号。
                - 不要输出思考过程，只输出 JSON。
                """),

        // ===== 单章卷纲重写 =====
        new TemplateDef(LlmNode.CHAPTER_REPLAN, "system", "单章卷纲重写系统提示", true,
                "你是网文主编，只输出合法 JSON，字符串内禁英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.CHAPTER_REPLAN, "user", "单章卷纲重写（换可行路径）", true, """
                任务：第 %d 章《%s》按现有卷纲目标生成反复失败，需要换一个写法。失败原因：
                %s
                现目标：%s
                现钩子：%s
                请重写该章的 title/goal/hook/time_note：目标必须换一条可行路径完成本章在卷中的使命（可改事件、改场景、改信息揭示顺序），不得与相邻章（第 %d、%d 章）目标雷同。
                只输出 JSON：{"title":"…","goal":"…","hook":"…","time_note":"…"}
                字符串值内部禁止英文双引号，引用一律用「」。

                %s

                【账本上下文】
                %s
                """)
    );
}
