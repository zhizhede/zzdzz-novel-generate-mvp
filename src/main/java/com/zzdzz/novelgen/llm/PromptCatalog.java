package com.zzdzz.novelgen.llm;

import java.util.List;

/**
 * 提示词注册表：各 LLM 节点 system/user 模板与运行时拼装段的集中目录，启动时由 PromptTemplateService 同步落库。
 * exact=true 为 format 模板（%s/%d 为运行时占位），exact=false 为 {key} 拼接段（占位由代码填参）——两类运行时都库值优先、代码块为回退，
 * 即目录里没有「仅浏览不接库」的行：凡发给 LLM 的固定文案都在此登记（素材库·提示词页签可编辑）。
 * 代码模板改动后：未人工定制的行会在下次启动自动对齐（version+1）；custom 行不受影响，可前端一键重置。
 */
public final class PromptCatalog {

    public record TemplateDef(String node, String phase, String title, boolean exact, String content) {}

    /** node|phase → 条目索引（Holder 惯例：首次访问才构建，此时 ALL 已就绪；重复条目即炸）。 */
    private static final class IndexHolder {
        static final java.util.Map<String, TemplateDef> INDEX = buildIndex();

        private static java.util.Map<String, TemplateDef> buildIndex() {
            java.util.Map<String, TemplateDef> idx = new java.util.HashMap<>();
            for (TemplateDef d : ALL) {
                if (idx.put(d.node() + "|" + d.phase(), d) != null) {
                    throw new IllegalStateException("PromptCatalog 重复条目: " + d.node() + "/" + d.phase());
                }
            }
            return java.util.Map.copyOf(idx);
        }
    }

    /** 目录内模板正文（node+phase 定位）：业务调用点的唯一回退，调用点不得再内联提示词文本。 */
    public static String contentOf(String node, String phase) {
        TemplateDef d = IndexHolder.INDEX.get(node + "|" + phase);
        return d == null ? null : d.content();
    }

    private PromptCatalog() {}

    public static final List<TemplateDef> ALL = List.of(

        // ===== 章纲 =====
        new TemplateDef(LlmNode.OUTLINE, "system", "AI 章纲系统提示", true,
                "你是网文章纲规划器，只输出合法 JSON，不要任何解释或 markdown 代码块。"
                        + "字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.OUTLINE, "reject_suffix", "章纲·人工打回意见注入句（拼在卷纲目标后）", false,
                "（上一版内容已被人工打回，打回意见：{reason}。本次规划必须针对性回应上述意见，调整场景设计）"),

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
        // 场景 system = style_packs 规则正文（按书落库，规则提炼/人工编辑产出）。
        // 曾有全局兜底"量化风格画像"（夜班守则口径）叠加进所有无规则书——与本书指纹目标互相矛盾且污染文风，
        // 已移除：量化口径唯一来源是 GateService.fingerprintGuidance 按本书基线生成（fingerprint_targets 段）。
        new TemplateDef(LlmNode.SCENE_DRAFT, "world_placeholder", "世界观缺失占位（packer.world 拼接用）", true,
                "（本书未配置独立世界观文档——以全书大纲、类型标签与素材卡为准；不得发明与上述设定矛盾的地理、组织或力量体系）"),

        new TemplateDef(LlmNode.STYLE_RULES, "system", "文风规则提炼系统提示", true,
                "你是文风分析师。从小说语料中提炼可执行的写作规则，供写手写同品类新书时逐条遵循。只输出规则列表：每行一条、以 - 开头、最多 15 条；不要解释、不要标题、不要寒暄。"),
        new TemplateDef(LlmNode.STYLE_RULES, "user", "文风规则提炼（语料节选→规则列表）", true, """
                品类：%s
                语料节选如下：
                %s
                提炼要求：
                - 只提炼可观察、可执行的文风规则：叙述视角与人称习惯、对话风格与标点规范、描写密度、句长节奏、遣词偏好、段落排版；
                - 对话规则必须明确到标点层面（引号内句读、引号后与叙述的衔接方式）；
                - 禁止提炼情节、人物设定或题材套路；
                - 每条规则具体到写作动作（写什么/怎么写/禁止什么），不要空话。
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
                - 禁止复沓：同一句或近似句、同一动作或环境细节，在本章内只许出现一次；【上一场景已写内容】里出现过的细节不得原样复写（复沓是本章废笔之首）。
                - 比喻（像/仿佛/如同）每千字不超过 %s 个；「不是……是……」式修辞每场景最多 2 次。
                - 观察只许作为行动的前奏——每个观察必须引出下一个动作或决定。

                %s

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

        new TemplateDef(LlmNode.SCENE_REVISE, "user", "场景门禁重写（{key} 拼接段：场景包+上一稿+门禁意见）", false,
                "{scene_user}\n\n【你上一稿】\n{draft}\n\n【门禁意见（只改被点名的问题，保持其余原样）】\n{gate_feedback}\n\n只输出修订后的完整正文。"),

        // ===== 场景工艺段（写法约束，注入 scene user 的 craft 槽位） =====
        new TemplateDef(LlmNode.SCENE_DRAFT, "opening_redlines", "本章开篇红线（第一场景注入）", false, """
                【本章开篇红线（本章第一个场景，逐条硬性执行）】
                - 必须紧接上一章结尾的情境：同一时间、同一地点、同一组在场人物；读者读完前 3 行就能定位「这章接在哪之后」。
                - 上一章结尾留下的钩子必须在场：开篇就是对它的回应、后果或直接推进，不是另起炉灶。
                - 但禁止复述上一章结尾的任何句子，也不要原地停留——第一段就要让情节往前走一步。
                - 从动作、对白、威胁或反常细节切入，禁止环境/天气/氛围铺陈开篇；前 3 行内必须抛出新信息或新威胁。
                - 禁止情绪直给（如「他很紧张」），情绪用动作与细节承载。
                """),

        new TemplateDef(LlmNode.SCENE_DRAFT, "craft_opening", "人类作者开篇范例（首场景审美对齐）", false,
                "【人类作者开篇范例（本作真实章节的前三行——学它的切入方式与信息密度，禁止照抄其内容与意象）】\n{examples}"),

        new TemplateDef(LlmNode.SCENE_DRAFT, "craft_dialogue", "对白推进范例（全场景注入）", false,
                "【对白推进范例（本作真实章节——情节靠人物说话，学这个节奏与密度，禁止照抄内容）】\n第{chapter_no}章：\n{excerpt}"),

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
        new TemplateDef(LlmNode.READER_REVIEW, "system", "读者评审五问（%s=注水率软阈值，%s=硬上限；书级 gate_config 可覆盖）", true, """
                你是一个没耐心的网文读者，刷手机时点开了这一章。你只关心「想不想继续读」，只回答下列问题：
                1. hook 前 3 行：会不会继续往下读？（环境/氛围/抒情式开场、或与上章结尾接不上=不会）
                2. stakes 这场戏：谁想要什么？什么在阻止？（说不出来=没有戏剧张力）
                3. continuity 读完前 10 行，能否定位上一章结束时的情境（时间/地点/在场人物）？（定位不到=衔接断裂）
                4. fat 与剧情无关、删掉后读者不会少知道任何事的纯装饰描写（给微动作写人物志、连篇比喻、静态观察），占比大约多少？
                5. consequence 上下文给出了【上一章事件后果】（上一章的目标、章末钩子与实际收束）。本章是否与之对接——给出兑现、交代或明确推进？（完全无视另起炉灶=fail）
                6. repeat 章内有没有原句复用或近似句反复（同一句、同一动作或同一环境细节在章内出现两次以上即算；成段反复=复沓）？把重复的原句列出来并标出现次数。
                只输出 JSON：
                {"verdict":"pass|blocker","hook":"pass|fail","stakes":"pass|fail","continuity":"pass|fail","consequence":"pass|fail","fat_ratio":0.4,"skip_quotes":["可整段删除的原句"],"repeat":["重复句（×次数）"],"issues":["具体问题（引用原句）"]}
                规则：
                - 引用原文一律用「」；字符串值内部禁止英文双引号。
                - hook/stakes/continuity/consequence 任一 fail → verdict=blocker。
                - fat_ratio 是报告项：大于 %s（软阈值）只提示偏水，不否决；只有大于 %s（硬上限）才判 blocker。连贯性永远比注水重要，不要为注水否决剧情完整的章节。
                - repeat 是报告项：只列清单不否决，清单会直接喂给下一稿重写（复沓由重写环节清理）。
                - skip_quotes 只能列纯装饰句；推进剧情、刻画人物、交代信息的句子一律不许进清单。
                - 你只管「想不想往下读」，错别字与设定连续性是另一位审校的事，不要报。
                - 不要输出思考过程，只输出 JSON。
                """),

        new TemplateDef(LlmNode.READER_REVIEW, "user", "读者评审输入（{key} 拼接段）", false, """
                【上一章结尾（衔接定位基准）】
                {prev_tail}

                【上一章事件后果】
                {prev_brief}

                【本章目标】{goal}

                【第 {chapter_no} 章全文（评审对象）】
                {full_text}

                只输出 JSON。
                """),

        new TemplateDef(LlmNode.READER_FIX, "system", "读者重写系统提示", true,
                "你是网文编辑，任务是让这一章「每一行都值得读」：删注水、保情节、补张力。"),

        new TemplateDef(LlmNode.READER_FIX, "user", "读者重写（弃书理由驱动的整章重写，剧情人设优先）", true, """
                任务：修订第 %d 章全文。没耐心的网文读者给出以下弃书理由：
                %s
                要求：情节节拍、关键信息与对白立场全部保留，人物性格与说话方式不得改变，任何剧情节拍不得删除或合并；
                删掉全部纯装饰描写与重复观察；禁止复沓——同一句或近似句在章内只保留一次，同一动作/环境细节不得复写，
                清单点名的重复句一律删到只余一处；推动情节的对白可以增加；篇幅与保留剧情冲突时优先保剧情，字数可低于目标。
                分行节奏与风格特征保持本书原貌；直接输出修订后的完整正文，不要输出思考过程。
                本章篇幅约束：%s。

                【第 %d 章全文（在此版本上修改）】
                %s
                """),

        new TemplateDef(LlmNode.READER_FIX, "user_recover", "读者重写·恢复扩写（删过头后补回情节节拍）", true, """
                任务：第 %d 章上一稿删注水后只剩约 %d 字，低于本章下限 %d 字。
                请把被删掉的情节节拍恢复为对白与动作，禁止新增环境/氛围/心理铺陈，目标 %d–%d 字；
                直接输出修订后的完整正文，不要输出思考过程。

                【弃书理由清单（删除仍然成立，不得恢复纯装饰段落）】
                %s
                【当前稿（在此版本上扩写）】
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

        new TemplateDef(LlmNode.AI_REVIEW, "user", "AI 审校输入（{key} 拼接段）", false, """
                【世界设定与大纲】
                {world}

                【人物卡】
                {characters}

                【世界状态（上一章结束时）】
                {world_state}

                【近章事实账】
                {digests}

                【上一章结尾】
                {prev_tail}

                【第 {chapter_no} 章全文（审校对象）】
                {full_text}

                审校以上全文，只输出 JSON。
                """),

        new TemplateDef(LlmNode.AI_REVIEW_REVISE, "system", "审校修订系统提示", true,
                "你是执行审校修订的网文编辑，只做被点名的最小修改。"),

        new TemplateDef(LlmNode.AI_REVIEW_REVISE, "user", "审校修订（硬伤最小修复）", true, """
                任务：修订第 %d 章全文。语义审校发现以下必须修复的问题：
                %s

                【事实基准（改稿必须与它一致）】
                %s

                要求：只修被点名的问题（错字改字、矛盾句最小改写），严禁改动情节走向与分行节奏，
                总字数变化控制在 ±10%% 内。
                **若正文与事实基准冲突，以基准为准改正文**——时间、地点、随身物品、人物在不在场、同行了多久，
                这几样最容易漂；改完请自查一遍，不要只改被点名的那一句而留着同一处矛盾的其他表述。
                基准分两块，**「上一章结束时的事实状态」是全书时间线/位置/物品的账，时间口径一律以它为准**；
                其下的「上一章发生了什么」只是情节梗概，里面的时间跨度若与账不一致，不得据它改动正文的时间表述。
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

        new TemplateDef(LlmNode.DIGEST, "time_anchor", "digest·时间锚点段（章行有 time_note 时注入）", false,
                "【时间锚点】本章距上一章：{time_note}（state.time 必须体现该推进）\n\n"),

        new TemplateDef(LlmNode.DIGEST, "ledger", "digest·已有伏笔账本段（防同义重复提议）", false,
                "【已有伏笔账本（同义勿重复提议）】\n{rows}\n"),

        new TemplateDef(LlmNode.DIGEST, "user", "digest 输入（{key} 拼接段）", false,
                "{time_anchor}{ledger}【本章全文】\n{full_text}"),

        new TemplateDef(LlmNode.WORLD_STATE, "system", "世界状态记录员系统提示（%s=state 字段规格）", true,
                "你是世界状态记录员。读完本章，输出本章结束时刻的结构化状态快照，只输出 JSON：\n{%s}\n规则：只记硬事实；人名用规范名；拿不准的不写；字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.WORLD_STATE, "user", "世界状态回填输入（存量章回填）", false,
                "{full_text}\n\n只输出 state JSON。"),

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
                %s
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

        new TemplateDef(LlmNode.VOLUME_PLAN_REVIEW, "user", "卷纲审校（连续性/重复/伏笔/节奏/复刻五查）", true, """
                【待审卷纲】
                %s
                【对照材料（人物设定卡 + 账本）】
                %s
                %s
                %s
                审校清单：① 连续性——是否与世界观/人物卡/世界状态/事实账矛盾（人物已死复活、物品凭空转移、时间倒流）；
                ② 重复——卷内相邻章目标是否雷同、是否与往卷炒冷饭；③ 伏笔——planted 未回收项是否被安排回收或给出悬置理由、proposed 取舍是否合理；
                ④ 节奏——张弛是否有曲线、卷尾钩子是否成立。
                ①中「凭空发明人物」的判定口径：**衍生书允许新创主角与次要人物**——只要新人物的力量体系/身份背景与世界观自洽、不与既有人物的既定事实矛盾，就不算硬伤；
                设定卡里没有某个名字本身不是问题，与卡中人物/设定发生冲突才是。同类判定：「新地点/新物品」同理，自洽即可。
                只输出 JSON：{"verdict":"PASS"或"BLOCKER","issues":["问题（指明章号）"]}
                存在必须修复的硬伤才 BLOCKER；风格偏好类意见写进 issues 但给 PASS。
                """),

        // 衍生书复刻判据段（sourceSampleId 存在时由 VolumePlanService 注入第 4 个 %s；非衍生书传空串）
        new TemplateDef(LlmNode.VOLUME_PLAN_REVIEW, "derive_no_copy", "卷纲审校·衍生复刻判据段（含样本骨架）", false, """
                【衍生复刻红线（最高优先级）】本书为样本衍生新作：规划行的主角不得为样本原书人物或其仅换姓名的对应物，主线节拍与标志性桥段不得与下方原书骨架同序同构——发现复刻即 BLOCKER，问题写明「复刻样本剧情」。
                【样本原书剧情骨架（仅供对照禁区，禁止落实进规划行）】
                {skeleton}
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

        // ===== 剧情换皮（RESKIN）：保住剧情骨架，把人名/场景/职业/实体/结局执行者全部随机重造 =====
        new TemplateDef(LlmNode.DERIVE_RESKIN, "system", "剧情换皮系统提示", true,
                "你是换皮改编师：只搬剧情骨架，不搬任何外衣。只输出合法 JSON，字符串内禁英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.DERIVE_RESKIN, "skin", "剧情换皮·换皮设定（每次随机一套新外衣）", true, """
                任务：为一部书定一套**全新的外衣**。本次随机种子：%s（同样的种子才允许产出同样的外衣）。
                要求：不得与本参考样本的题材/时代/职业/组织/地名/人名/器物/生物重复；越不像越好，但仍要能承载同类剧情。
                %s
                %s
                输出 JSON：
                {"genre":"题材（如：都市灵异/星际打捞/民国探案）",
                 "world":"世界观两三句：时代、地点类型、世界的核心规则或禁忌",
                 "protagonist":"主角名（中文姓名，克制不中二）",
                 "ensemble":"主要配角命名与身份风格（如：店长、退休刑警、外卖站长）",
                 "characters":[{"name":"主角与主要配角的名字（3-6 个）","note":"一句话身份"}],
                 "tone":"叙事基调一句话",
                 "outline":"全书大纲 markdown（## 核心设定 / ## 主线 / ## 分卷走向 三段），只写新外衣下的主线，不得出现参考样本的任何专有名词"}
                字符串值内部禁止英文双引号，引用一律用「」。characters 是本书**唯一合法人名表**，后文各章只许用这些名字。
                """),

        new TemplateDef(LlmNode.DERIVE_RESKIN, "chapter", "剧情换皮·逐章（保节拍、换外衣）", true, """
                换皮设定（全书统一，不得改动）：
                %s

                任务：把下面这一章的剧情**换成上述新外衣**，但**剧情骨架必须一比一保留**：
                - 节拍数量、顺序、每一拍的功能（谁想做什么 / 遇到了谁 / 对方提出什么 / 如何凑人 / 结果如何）全部不变；
                - 结局的「形状」必须保留（如原文是团队全军覆没，新章也必须是全军覆没），但由谁造成可以随新世界改；
                - 人名、地名、组织名、职业、器物、生物全部换成本世界的对应物；
                - **人名只许用换皮设定 characters 里的名字**，不得新造人名，也不得残留样本里的任何人名；
                - 不得新增或删除节拍，不得改变事件因果顺序；
                - **本章距上一章的时间跨度必须原样保留，写成 time_note**（原章跨几年，新章也跨几年；原章是「次日」，新章才是「次日」）——不得把长篇的岁月压缩成「紧接」「次日」，否则人物年龄、子嗣、伤病、技艺全部对不上；
                - summary 与 beats 的字数量级与原文相当。

                原章（参考样本，**只借结构**）：
                章号：第 %s 章
                时间跨度：%s
                摘要：%s
                节拍：%s

                输出 JSON：
                {"summary":"换皮后的本章剧情摘要","time_note":"本章距上一章的故事时间跨度（与原章一致，如「紧接上一章」「入城后第三年」「约十年后」）","beats":[{"goal":"场景目标","conflict":"冲突","outcome":"收束"}],
                 "hook":"换皮后的章末钩子"}
                字符串值内部禁止英文双引号，引用一律用「」。
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
                """),

        // ===== 导入小说深度解析（样本资产化） =====
        new TemplateDef(LlmNode.SAMPLE_CHAPTER, "system", "样本章解析系统提示", true,
                "你是小说结构分析师。读完给定章节原文，输出结构化分析；只输出一个 JSON 对象，字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.SAMPLE_CHAPTER, "user", "样本章解析（摘要+场景拆解+实体抽取）", true, """
                任务：分析下面这一章原文（长章可能是多章合并或无标题伪章），输出 JSON：
                {"summary":"本章剧情摘要 150-300 字","beats":[{"goal":"场景目标","conflict":"冲突","outcome":"收束"}],
                 "entities":[{"name":"规范名","aliases":["别名"],"kind":"character|item|location|org|phenomenon|landmark|disaster|misc",
                   "note":"一句话身份","relations":[{"target":"对象名","kind":"关系","note":"说明"}]}],
                 "hooks":["章末钩子"]}
                要求：entities 覆盖本章全部有名字的实体（人物为主，含重要物品/地点/组织/现象），同一实体只出一行、别名并aliases；relations 只记本章明示的关系；beats 按场景顺序 2-6 条。

                【章节：%s】

                【章节原文】
                %s
                """),

        new TemplateDef(LlmNode.SAMPLE_VOLUME, "system", "样本卷汇总系统提示", true,
                "你是网文主编，负责把逐章摘要合成为卷级结构卡；只输出一个 JSON 对象，字符串值内部禁止英文双引号。"),

        new TemplateDef(LlmNode.SAMPLE_VOLUME, "user", "样本卷汇总（arc/主线/节奏）", true, """
                任务：下面是同一卷的逐章摘要，合成卷级结构卡，输出 JSON：
                {"arc":"本卷主线一句话","summary":"本卷剧情梗概 200-400 字",
                 "pacing_note":"节奏画像（开局/推进/高潮/收束各占约几成，钩子密度如何，80字内）",
                 "key_turns":["关键转折点"]}
                要求：只依据给定摘要，不发明不存在的事件。

                【%s】

                【逐章摘要】
                %s
                """),

        new TemplateDef(LlmNode.SAMPLE_OUTLINE, "system", "全书大纲合成系统提示", true,
                "你是网文总编，负责从卷级结构与主要角色卡合成全书大纲；只输出一个 JSON 对象，字符串值内部禁止英文双引号。"),

        new TemplateDef(LlmNode.SAMPLE_OUTLINE, "user", "全书大纲合成（premise/主线/arcs/主题/结局）", true, """
                任务：依据下面的卷级结构卡与主要角色卡，合成全书大纲，输出 JSON：
                {"premise":"核心设定与开局钩子（100字内）",
                 "main_plot":"主线剧情梗概（300-500字，交代起承转合）",
                 "arcs":[{"title":"阶段名","span":"章节范围","summary":"该阶段剧情（100字内）"}],
                 "themes":["主题"],"ending":"结局走向（80字内）"}
                要求：arcs 按 3-8 个阶段划分全书；只依据给定材料，不发明不存在的事件。

                【全书概况】
                %s

                【卷级结构】
                %s

                【主要角色卡】
                %s
                """),

        new TemplateDef(LlmNode.SAMPLE_WORLD, "system", "世界观文档合成系统提示", true,
                "你是设定考据员，负责从卷级结构与设定类实体卡合成世界观文档；输出 markdown 纯文本（不要 JSON、不要标题记号外的多余格式）。"),

        new TemplateDef(LlmNode.SAMPLE_WORLD, "user", "世界观文档合成", true, """
                任务：依据下面的卷级结构与设定类实体卡（地点/组织/现象/物品），写一份世界观设定文档（markdown，600-1200 字），分节：世界背景与规则、力量/核心体系、重要地点、重要组织、关键设定与禁忌。
                要求：只写材料中出现过或可由其直接推出的设定；不得发明原文没有的规则。

                【卷级结构】
                %s

                【设定类实体卡】
                %s
                """),

        // ===== 导入书籍·解析链：素材卡提取（大纲/世界观复用上面的 SAMPLE_OUTLINE/SAMPLE_WORLD，避免双源漂移） =====
        new TemplateDef(LlmNode.BOOK_CARDS, "system", "书籍素材卡提取系统提示", true,
                "你是小说设定档案员。读章节结构与剧情摘要，抽出这本书的设定层素材卡；"
                        + "只输出一个 JSON 对象，字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.BOOK_CARDS, "user", "书籍素材卡提取（章节摘要→设定卡）", true, """
                任务：读下面这本书的章节结构与剧情摘要，抽出它的**设定层素材卡**，输出 JSON：
                {"cards":[{"name":"规范名","kind":"character|item|location|org|phenomenon|landmark|disaster|misc",
                  "aliases":["别名"],"summary":"一句话身份或用途（≤40 字）",
                  "content":"书中的关键设定、当前状态、与其他卡的关系（100-300 字）",
                  "pinned":true,"sourceChapter":首次出现的章号}]}
                要求：①只抽摘要里真实出现过的实体，按重要性取前 %s 张（人物优先，含关键物品/地点/组织/现象/地标/灾害）；
                ②同一实体只出一行、别名并入 aliases；③pinned 只给贯穿全书的常驻设定（主角、核心设定），不超过 6 张；
                ④kind 只能用给定英文枚举；⑤sourceChapter 给首次出现的章号，不确定就给 null。

                【书：%s】

                【章节结构与摘要】
                %s
                """),

        // 解析链的章纲步：**从已有正文反推**（不是写之前的规划）——导入书成稿章要的是「这章实际怎么分场」的事后拆解。
        new TemplateDef(LlmNode.BOOK_CHAPTER_OUTLINE, "system", "章纲反推（已有正文→场景拆解）系统提示", true,
                "你是小说结构分析师。给你一章已成稿的正文，你要把它**实际**分成的场景拆出来。"
                        + "只输出合法 JSON，不要任何解释或 markdown 代码块。"
                        + "字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.BOOK_CHAPTER_OUTLINE, "user", "章纲反推（已有正文→场景拆解）", true, """
                任务：把第 %d 章《%s》的**已有正文**（约 %d 字）按实际分场拆解出来。
                要求：
                - 只描述这一章**实际写了什么**，不得新增原文没有的情节、人物或设定；
                - 按原文的场面/时空转换切成 2-3 个场景，合起来覆盖全章（场景之间不重叠，也不漏掉章末）；
                - words 填该场景在原文里大约占的字数（各场景相加接近全章字数）；
                - must_not 一律给空数组 []——这是事后拆解，「禁止写什么」在此没有对象。

                【本章正文】
                %s

                只输出 JSON，格式：
                {"scenes":[{"no":1,"goal":"本场景实际完成了什么","present":["实际出场的人物/物件"],"must_reveal":["本场景实际让读者知道的信息"],"must_not":[],"words":900}]}
                """),

        new TemplateDef(LlmNode.SAMPLE_MERGE, "system", "实体名归并判定系统提示", true,
                "你是数据清洗员：判断实体列表里哪些行指的是同一个实体；只输出一个 JSON 对象，字符串值内部禁止英文双引号。"),

        new TemplateDef(LlmNode.SAMPLE_MERGE, "user", "实体名归并判定", true, """
                任务：下面是从同一本小说各章抽出的实体行（名/别名/类型/备注）。找出指同一实体的行组，输出 JSON：
                {"groups":[{"canonical":"保留的规范名","members":["并掉的行名"]}]}
                要求：只归并有把握同指一人的（如 本名/代号/译名/尊称）；没有可归并的就输出空 groups；单个别名不同的不要归并。

                【实体行】
                %s
                """),

        // ===== 衍生参数 AI 推荐（开书向导「AI 帮我定」） =====
        new TemplateDef(LlmNode.SAMPLE_PARAMS, "system", "衍生参数推荐系统提示", true,
                "你是网文策划，依据样例小说的结构画像为衍生新书推荐参数；只输出一个 JSON 对象，字符串值内部禁止英文双引号。"),

        // ===== 运行时拼装段（common/scene/digest 命名空间：getSection {key} 占位接库，素材库·提示词可编辑） =====
        new TemplateDef(LlmNode.SCENE_DRAFT, "derive_pov", "衍生段·叙事视角（有 POV 配置时注入）", false, """
                【叙事视角（必须遵守）】
                {pov}；主视角：{povCharacter}。除全知视角外，非主视角人物的内心活动不可直写，只能通过言行与观察呈现。
                """),

        new TemplateDef(LlmNode.SCENE_DRAFT, "derive_density", "衍生段·情节密度（有掺水量配置时注入）", false, """
                【情节密度要求】
                {density}
                """),

        new TemplateDef(LlmNode.SCENE_DRAFT, "derive_tags", "衍生段·类型标签（衍生书注入）", false, """
                【类型标签（本书的类型基调与标志性元素，规划与行文必须贴合）】
                {tags}
                """),

        new TemplateDef(LlmNode.SCENE_DRAFT, "derive_redline", "衍生段·差异红线（衍生书注入，防复述样本原书）", false, """
                【衍生差异红线（最高优先级）】本书为样本衍生新作，不是样本的复述或改编：
                - 禁止复述样本原书的情节走向、桥段与章节结构；
                - 本书主角与主线必须为原创新人物新事件（样本素材卡中的原书主角只能作为背景设定存在，不得担任本书主角）；
                - 只沿用其世界观规则、力量体系与类型套路。
                """),

        // MIGRATE（剧情迁移）与上面的红线段互斥注入：红线命令「禁止复述原书情节、主角必须原创」，
        // 迁移书要的恰好相反（按迁入章纲逐章走）。两段同注会让模型无所适从，故由 ContextPackerService 二选一。
        new TemplateDef(LlmNode.SCENE_DRAFT, "derive_migrate", "衍生段·剧情迁移口径（MIGRATE 注入，替代差异红线）", false, """
                【剧情迁移（最高优先级）】本书按样本剧情逐章迁移生成，不是原创改写：
                - 严格按本章章纲与场景拆解推进：事件、顺序、结果都照章纲走，不得另编情节或改动走向；
                - 章纲里出现的人名就是本书的定名（主角为「{povCharacter}」），**不要改名、不要另起同名角色**；
                - 允许在场景内部细化动作、对白、感官与心理，但不得新增改变走向的事件。
                """),

        // 文风指纹量化目标（GateService.fingerprintGuidance 按书生成；有指纹才注入——第一稿就朝门禁及格线写）
        new TemplateDef(LlmNode.SCENE_DRAFT, "fingerprint_targets", "写作段·文风指纹量化目标（与机械门禁同口径）", false, """
                【文风指纹指标（机械门禁逐条硬判，超限直接打回重写——写作时同步自查）】
                {targets}
                """),

        new TemplateDef("common", "derive_volume", "衍生段·卷规划版（POV/密度/标签/红线合并）", false, """
                【叙事视角（必须遵守）】
                {pov}；主视角：{povCharacter}。除全知视角外，非主视角人物的内心活动不可直写，只能通过言行与观察呈现。
                【情节密度要求】
                {density}
                【类型标签（本书的类型基调与标志性元素，规划与行文必须贴合）】
                {tags}
                【衍生差异红线（最高优先级）】本书为样本衍生新作，不是样本的复述或改编：禁止复述样本原书的情节走向、桥段与章节结构；本书主角与主线必须为原创新人物新事件；只沿用其世界观规则、力量体系与类型套路。
                """),

        // ===== 全局账本段（packLedgers 注入卷规划/单章重规划上下文） =====
        new TemplateDef("common", "ledger_plans", "账本·已有卷纲段（不得重复桥段，须衔接走向）", false,
                "【已有卷纲（往卷已写与当前规划；不得重复其桥段，须衔接其走向）】\n{rows}\n"),

        new TemplateDef("common", "ledger_digests", "账本·事实账段（最近硬事实）", false,
                "【事实账（最近硬事实）】\n{rows}\n\n"),

        new TemplateDef("common", "ledger_worldstate", "账本·世界状态段（截至第 {until_no} 章结束）", false,
                "【世界状态（截至第 {until_no} 章结束，必须遵守——物品归属与位置不得凭空变化）】\n{rows}\n\n"),

        new TemplateDef("common", "ledger_foreshadows", "账本·伏笔账本段（未回收项）", false,
                "【伏笔账本（未回收项；proposed=自动提议待排期，被引用即采纳；planted=已埋待回收，被引用即安排回收）】\n{rows}\n"),

        new TemplateDef("common", "retro_section", "上卷复盘要点段（有复盘报告时注入卷规划）", false,
                "【上卷复盘要点（第 {vol_no} 卷复盘结论，本卷规划必须做出回应：点名的悬置伏笔优先安排兑现或给出理由）】\n{compact}\n\n"),

        // ===== 卷规划上下文框架段（packVolumePlan） =====
        new TemplateDef("common", "volume_world", "卷规划·世界观与大纲头段", false,
                "【世界观与全书大纲（必须遵守，不得发明矛盾设定）】\n{world}\n\n"),

        new TemplateDef("common", "volume_seed", "卷规划·本卷种子大纲头段", false,
                "【本卷种子大纲（最高优先级，须全部落实）】\n{seed}"),

        new TemplateDef("common", "volume_seed_empty", "卷规划·无种子大纲时的占位句", false,
                "（无——请基于上方全局账本自主设计本卷主线，并在 brief 中说明关键决策）"),

        // ===== JSON 校验喂回（LlmJson 重试轮追加到末条 user 之后） =====
        new TemplateDef("common", "json_retry_feedback", "JSON 输出不合规喂回句（LlmJson 重试）", false,
                "【上一次输出不合规：{reason}。请重新输出，只输出合法 JSON。】"),

        // ===== 卷规划重试喂回（结构校验/AI 审校未过原因注入下一轮） =====
        new TemplateDef("common", "plan_retry_feedback", "卷规划重试·上轮未过原因注入段", false,
                "\n\n【上一轮未过原因（本轮必须修正）】\n{feedback}"),

        new TemplateDef("common", "plan_span_free", "卷规划·章数自由口径（无目标章数时）", false,
                "章数 6-15 章由你定夺（决定本卷篇幅，在 no 字段连续编号体现）"),

        new TemplateDef("common", "plan_span_target", "卷规划·章数目标口径（衍生配置）", false,
                "章数目标 {target} 章（允许 ±{slack} 章，在 no 字段连续编号体现）——这是本书的节奏设定，非建议"),

        new TemplateDef("common", "plan_budget_with", "卷规划·预算带口径（有带时）", false,
                "4. budget_min/budget_max 为单章字数预算，本书风格基线（源自品类预设）为 {lo}-{hi} 字，各章预算必须落在该带内。"),

        new TemplateDef("common", "plan_budget_without", "卷规划·预算带口径（无带时）", false,
                "4. budget_min/budget_max 为单章字数预算，参考往卷实际水平 2800-4000。"),

        new TemplateDef("digest", "state_spec", "世界状态快照字段规格（digest 输出结构）", false, """
                "state":{"time":"本章结束时的时间点：只认本章正文明确写出的时间线索（如『次日清晨』『三年之后』『又过了半月』）；正文只写局部时长（如『这半月掉的肉』说的是身体变化、『等了半个时辰』说的是单场等待）时，严禁据此反推总历时；正文没写推进量就沿用上一章的时间表述、只补正文支持的推进量，拿不准一律保守",
                 "locations":{"人名或重要物名":"所在位置"},
                 "possessions":{"人名":["随身携带的重要物品"]},
                 "new_promises":["本章新立下的承诺/约定/邀约"],
                 "unresolved":["本章留下的未解之谜或未回收伏笔"]}"""),

        new TemplateDef(LlmNode.SAMPLE_TAGS, "system", "样本标签提取系统提示", true,
                "你是网文分类编辑，给小说打类型与特征标签；只输出一个 JSON 对象，字符串值内部禁止英文双引号。"),

        // ===== 开书向导·AI 生成全书大纲草稿 =====
        new TemplateDef(LlmNode.DERIVE_OUTLINE, "system", "全书大纲草稿生成系统提示", true,
                "你是网文总编，为一本新书创作全书大纲。只输出大纲正文（markdown），不要 JSON、不要任何解释或开场白。"),

        // 克隆世界约束段（样本资产克隆后注入；库值优先可前端编辑，占位 %1=世界观文档 %2=素材卡名单）
        new TemplateDef(LlmNode.DERIVE_OUTLINE, "world", "克隆世界观约束段（样本资产克隆后注入）", true,
                "【共用世界观（新故事必须发生在该世界内；可少量引用原书人物为配角，但主角、主线与情节必须完全原创，禁止复刻样本的剧情线与桥段）】\n%s\n"),

        new TemplateDef(LlmNode.DERIVE_OUTLINE, "user", "全书大纲草稿生成（基本信息+衍生设定）", true, """
                任务：为下面的新书创作**全新原创**的全书大纲，供作者过目修改（之后每一章生成都携带它作为方向约束）。分节输出：## 主题与核心悬念、## 主线（起承转合 300-500 字）、## 分卷走向（每卷一行：卷名+主线任务+卷尾钩子）、## 主要人物（3-6 人：名字/身份/动机/弧光）、## 题材基调。
                要求：分卷走向按 %d 卷规划；**情节、人物、桥段必须完全原创**——即使提供了样本的世界观或类型方向，也禁止复刻样本的剧情线、人物关系与桥段序列；全部内容须贴合类型标签与题材基调，悬念与钩子密度按节奏口径安排。

                【书名】%s
                【简介】%s
                【文风预设】%s
                【类型标签】%s
                【叙事视角】%s
                【节奏口径】%s（每卷约 %d 章）
                %s
                """),

        // 复刻重写（原大纲被 derive_originality 判复刻后的再生成；system 复用本节点 system）
        new TemplateDef(LlmNode.DERIVE_OUTLINE, "rewrite", "全书大纲·复刻判定后重写", true, """
                你此前为这本书生成的大纲被原创性审校判定为**复刻样本原书**，判定依据：
                %s

                请重写全书大纲：保持书名、简介、类型标签与世界观设定不变，但主角必须换成全新原创人物（不得使用原书人物名，也不得用仅换姓名的对应物），主线事件序列必须重新设计（不得沿用原书的起承转合顺序与标志性桥段）。分节输出：## 主题与核心悬念、## 主线（起承转合 300-500 字）、## 分卷走向（每卷一行：卷名+主线任务+卷尾钩子）、## 主要人物（3-6 人：名字/身份/动机/弧光）、## 题材基调。只输出大纲正文（markdown），不要 JSON、不要任何解释。

                【被判复刻的原大纲】
                %s
                """),

        // ===== 衍生大纲·原书复刻评审（书 9/10/11 换名复刻实证后的防线） =====
        new TemplateDef(LlmNode.DERIVE_ORIGINALITY, "system", "衍生大纲复刻评审系统提示", true,
                "你是原创性审校官，判定一本衍生新作的大纲是否复刻了样本原书的情节。只输出合法 JSON。"
                        + "字符串值内部禁止英文双引号，引用一律用「」。"),

        new TemplateDef(LlmNode.DERIVE_ORIGINALITY, "user", "衍生大纲复刻评审（换名重述也算复刻）", true, """
                任务：对照【样本原书骨架】与【原书主要人物】，判定下面的【新书大纲】是否构成对原书情节的复刻。**换名重述也算复刻**。

                判为复刻（copy=true）的口径（满足任一条）：
                - 大纲主角与原书主角为同一人物，或仅换了姓名的对应物（身份、核心关系、经历轨迹一致）；
                - 主线节拍与原书骨架同序同构（起承转合的关键转折一一对应，仅细节表皮不同）；
                - 原书的标志性桥段被直接搬用（序列关系 preserved）。

                不判为复刻（copy=false）的口径：
                - 只共用世界观、力量体系、地理与类型套路（同世界衍生允许）；
                - 原书人物仅作背景提及，不担任主角、不驱动主线；
                - 题材相似但事件序列是原创的。

                只输出 JSON：{"copy":true或false,"reasons":["判定依据（copy=false 时给空数组）"]}

                【样本原书骨架】
                %s

                【原书主要人物】
                %s

                【新书大纲】
                %s
                """),

        new TemplateDef(LlmNode.SAMPLE_TAGS, "user", "样本类型/特征标签提取", true, """
                任务：依据下面的书级材料给这本小说打标签，输出 JSON：
                {"tags":["标签1","标签2",…]}
                口径：5-15 个；每标签 2-6 字；覆盖三类——类型/题材（如 奇幻、都市、克苏鲁、言情）、体量节奏（如 短篇、长篇、快节奏、慢热）、标志性特征元素（如 不可名状、章鱼、狼人、剑与魔法、系统流）；
                只提取材料有依据的，宁缺毋滥。

                【书级材料】
                %s
                """),
        new TemplateDef(LlmNode.SAMPLE_PARAMS, "user", "衍生参数推荐（掺水量/POV/节奏/章数）", true, """
                任务：用户要以样例《%s》为蓝本衍生新书。依据下面的结构画像推荐衍生参数，输出 JSON：
                {"water":0,"pov":"第一人称|第三人称限知|第三人称全知|多视角轮换 之一","povCharacter":"主视角（人物名，多视角则留空）",
                 "chaptersPerVolume":10,"targetChapters":300,"pacingNote":"节奏说明 40-80 字","reason":"推荐理由 80 字内",
                 "tags":["衍生书类型/特征标签 5-10 个，如 奇幻、剑与魔法、快节奏——可沿用样例也可按衍生方向调整"]}
                口径：water 0=情节密度拉满的干货流，50=均衡，100=日常氛围舒缓流；chaptersPerVolume 3-30；
                targetChapters 按样例体量与题材惯例估（50-2000）；povCharacter 必须是材料中出现的主要人物。

                【结构画像】
                %s
                """),

        // ===== 素材卡注入段（MaterialCardService.render） =====
        new TemplateDef("material", "card_block_header", "素材卡·设定卡块头段（卡清单注入规划/审校/章纲）", false,
                "【设定卡（人物/物品/地点设定，必须遵守，不得发明矛盾设定）】\n{cards}"),

        // ===== 开发冒烟（SmokeRunner，--smoke.enabled=true 时运行） =====
        new TemplateDef(LlmNode.SMOKE, "exemplar_header", "冒烟·风格范例标题段", false,
                "【风格范例（逐字原文，严格模仿其分行节奏与口吻）】\n{exemplar}"),

        new TemplateDef(LlmNode.SMOKE, "user", "冒烟·续写任务", true,
                "任务：续写《人类、法师、地下城》。场景：早饭后，塞拉斯和坎德尔一起出门前往冒险家协会，"
                        + "路上坎德尔提到最近向导委托变多，感觉魔物又要溢出。写到协会门口为止。"
                        + "要求 300–500 字，只输出正文，不要任何解释。")
    );
}
