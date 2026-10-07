# 项目状态快照（2026-09-15）

> 易变内容。每次开发收尾请更新本文件；新会话先读 AGENTS.md 再读这里。

## 门禁卡边三因修复：反馈打全区间 + 修订给方向 + 密度上限可覆盖（2026-10-07，分支 `10/1007-门禁卡边反馈修复`，已 push `caba482`）

**背景**：书 60 ch3/ch4、书 61 ch2 反复被机械门禁卡边（章长差 2 字、行均两头翻、密度差 0.24），每次白烧 2 轮章级修订 + 1 次断点重跑，本轮咬人 3 次。诊断出三个叠加根因：

| 根因 | 实锤 |
|---|---|
| **修订反馈残缺**：`failedChecksText` 只打 baseline 不打 abs_max | 密度失败显示「基线null」——模型不知道 30.2 上限，**两次收敛到 30.44 卡死**；行均只显示中位数 23.68——模型朝中位数压，**过冲到 15.65 跌破真实下限 20.13**（真实带 [20.13, 31.95] 从未被告知） |
| **指纹缺密度基线**：预设 68 是外部工具导入的 9 指标子集 | 场景密度上限恒为硬编码 30.2；实测过稿场景密度全部 ≤29.68、未过稿 30.44 起堆积——「信息密度红线推高对白」与 30.2 天然摩擦 |
| **双指标拉扯无方向**：章长要增、行均要拆，一轮修 A 坏 B | 2 轮自愈预算耗尽 → FAILED → 只能断点重跑 |

**修复（一个提交）**：
- `failedChecksText` 按界别打全：双界→`（a~b）`、仅上限→`（上限b）`、仅基线→`（基线a）`；改 static package-private 便于单测
- `CHAPTER_REVISE` 模板加「指标修复方向」块（字数不足→只增不删补短行 / 行均超→拆长句 / 密度超→合并连续对白转述；多指标同时失败必须组合动作）；`SCENE_REVISE` 同款方向行——纯文本行，两模板 arity 不变
- 场景密度上限三级回退：**书级 gate_config 键 `scene_dialogue_density_max`** → 指纹 abs_max → 硬编码 30.2（第三类判据「只给开关」口径，默认行为不变）

**验证**：`mvn test` **340/340**（新增 ReviseFeedbackTest 8 条）；两个新模板 psql 核对入库。**未做**：ε 卡边豁免（刻意不做，先靠反馈修复观察）；指纹补密度基线（重提指纹牵动面大，留观察）。

**追加（同日，33 章验证跑第 1 章实弹，提交 `67b6d76` 已 push）**：反馈修复生效后（修订请求全部带上限、零「基线null」）密度仍在场景级连败 5 次（30.44/30.49 磨边界）→ **密度主判据上移章级**。实测定案：12 个已成稿章章级密度 20.7–33.8 稳定，失败全堆场景级 30.4–37；根因是 450 字场景单个「≈2.2/千字的方差——代码注释早就因同理把低界挪到章级，上界只治了一半。修复：章级新增密度主判据（三层回退键改名 `dialogue_density_max`）+ 场景级降为 1.5× 极端护栏（默认 45.3，只拦病理稿 55+）+ fingerprintChecks 跳过密度防双条目。`mvn test` **341/341**；恢复跑批实证断点复用（ASSEMBLE reuse=true）且场景密度 0 新失败。另一发现：样本 11 的 plot_nodes（换皮输入源）残留「失身/缠绵/床单殷红」→ 书 62 换皮被风控连拒 fail-fast；已清洗 3 行节点（全库 0 残留）后书 63 换皮 33 章 0 拒答——**红线管输出，样本/解析节点这类输入源要单独清洗**。

## 内容安全红线 + 零中文稿 NPE 修复（2026-10-07，分支 `10/1005-一致性加固与状态账本`，未提交）

**背景**：书 60 ch2 重跑时 MiniMax 内容审核连续拒答（返回 60 字符英文 `The request was rejected because it was considered high risk`），输入里的触发词是「失身/缠绵/床单殷红」——分布在章纲 goal、素材卡、大纲、换皮设定等**六个注入源**；拒答稿（零中文）又引爆了 `computeMetrics` 早退残缺 map 的既有 NPE（`Map.get` 拆箱 null → 整章 FAILED）。同日用户定调：**全链路加提示词限制，敏感情节一律用最人机的干句方式写**。

| 修复 | 落点 | 关键口径 |
|---|---|---|
| **① 数据层清洗** | 书 60 六源（canon/卡片/章行/场景/digest/world_states）高危词全量替换：「被设计失身」→「遭人设局暗算」、「回想那夜缠绵与床单殷红」→「想起那夜的蹊跷」 | 宽表正则终扫 0 残留 |
| **② 全链路提示词红线** | **15 个模板**加「内容安全红线」：生成侧 12（SCENE_DRAFT/OUTLINE/DERIVE_RESKIN skin+chapter/DERIVE_OUTLINE/VOLUME_PLAN/DIGEST/SAMPLE_CHAPTER/SAMPLE_VOLUME/SAMPLE_OUTLINE/BOOK_CARDS/CHAPTER_REPLAN）+ 修订侧 3（CHAPTER_REVISE/READER_FIX/AI_REVIEW_REVISE，防修订轮写回风险词）。统一口径：**性/暴力/犯罪敏情节只用报告式中性概述干句、禁用失身床戏缠绵等高危词、只写结果不写过程**；纯文本行不加 %s（arity 全锁不动） | `ContentSafetyRedlineTest` 3 条锁：15 模板字面「内容安全红线」+ 高危词表统一 + SCENE_DRAFT/OUTLINE/换皮/DIGEST arity 不变 |
| **③ NPE 修复（治本）** | `computeMetrics` 零中文/空文本从「只回 `{"cjk":0}` 残缺 map」改为**全量键零值**——消费方按正常键集取值不炸；零值=最差稿 → 长度/对白必不过 → 走既有「门禁不过带意见重写」自愈 | `GateZeroCjkMetricsTest` 5 条；旧契约测试 `GateMetricsTest.emptyTextYieldsZeroCjkOnly` 同步翻转（注明实弹依据） |

**实弹（书 61「红线验证·干净衍生书-勿留」，与书 59/60 同配置：样本 11+RESKIN+water=10+第一人称+预设 68，targetChapters=2）**：
- **换皮源头即干净**：四源高危词 0 命中；同一情节点书 60 写「被设计失身」、书 61 红线后写「遭人设计而心灰意冷」——正是要的中性干句；
- **62 次调用 / 58.5 万 tokens / 0 次 high risk 拒答**（对照组书 60 ch2 连续拒答）；
- **0 次 NPE**（`尝试异常` 计数为 0）；NPE 修复后即使拒答稿出现也只是「判无效重写」不再炸章；
- 时间锚（V42）落列正常（ch2 五场景 time_anchor 全填）、`pov_consistent` 六次检查全 ok、ch1 审校两轮收口 DIGESTED（3013 字）；
- ch2 转 PENDING_APPROVAL 是**读者评审结构性 blocker**（章首视角断裂：ch1 末第一人称 → ch2 开头第三人称「她」）——系统正常工作，非风控非 NPE，留人审；
- 过程中 ch2 场景 2/4 对话密度卡边（33/36 vs 30.2）靠断点重试过——与 10-06 同类的既有阈值问题，非本轮引入。

**测试**：`mvn test` **332/332**（324 + 红线 3 + NPE 5 − 旧契约改1）。**遗留**：书 60/61 均为探针（标题「勿留」）；对话密度/章长阈值卡边问题仍在（属 STATUS「第三类判据」）；书 61 ch2 待人工审批。

## 人称（POV）三层加固：读取链 + 章纲落列 + 门禁/审校双查（2026-10-06，分支 `10/1005-一致性加固与状态账本`，未提交）

**动机**：书 59 长篇换皮实测暴露人称漂移——配置「第一人称（主角）」但 ch3 整章第三人称、ch4 又跳回第一人称；此前人称只是场景提示词里的一句静态约束，无章级标识、无机械检查、审校不查这一维。

**读取链（单一出口，三个消费方共用）**：`DeriveSupport.effectivePov(chapterPov, bookPov)` ＝章级 `chapters.pov` → 书级 `derive_config.pov` → null（fail-open 不约束不检查，旧书零变化）。换皮/迁移书场景预物化、章纲步不跑，章级恒空——**书级回退正是它们的生效路径**。

| 层 | 改动 | 落点 |
|---|---|---|
| ① 场景注入 | packScene 按章取人称压过书级；`derive_pov` 模板加「全章自始至终保持这一人称，禁止章内中途切换」 | `ContextPackerService.deriveSection(novelId, chapterPov, sceneOnly)`、`PromptCatalog/derive_pov` |
| ② 章纲落列 | OUTLINE 与反推两模板输出结构加根级 `pov`；`validateScenes` 改返回**根节点**（原返回 scenes 数组会把根级 pov 静默丢掉，AGENTS 坑 14 同族）；`materialize` 收 `pov` 参数非空才写（模型漏字段不覆盖）；新 DAO `ChapterMapper.updatePov`。OUTLINE 提示词加一行「本书叙事人称：%s」（arity 15，调用点同步传参） | `OutlineService`、`ChapterDataService.updatePov`、V 无（列已存在，229 行全 NULL 是没接线不是缺列） |
| ③a 机械门禁 | `GateService.povCheck` 纯函数：**先剥对白只看叙述层**——期望第一人称而叙述层「我」==0 → 错配；期望第三人称而叙述层「我」≥30/千字 → 错配；多视角/全知/未知/叙述样本 <100 字 → null 不查。**`pov_check_block`（tuning，默认 0）只报不拦**：报告 ok 如实记录但不进 passed 计算（沿用 `reader_structural_block` 先例，先观察误报再开拦截） | `GateService.evaluateChapter` + `effectivePov` 私有方法 |
| ③b AI 审校第 5 查 | system 四查→五查，新增 `pov` type（整章级错配判 blocker，段落切换/对白人称不报，未配置跳过）；user 模板加 `{pov}` 段；修订轮事实基准加「叙事人称（全章必须与此一致）」 | `PromptCatalog/AI_REVIEW`、`ReviewService.userPrompt/factBaseline` |

**证据**：`mvn test` **316/316**（新增 16：`GatePovCheckTest` 8 条判据纯函数、`OutlineChapterPovTest` 6 条含 OUTLINE arity=15 锁与模板文案锁、`DeriveSupportTest` +1 effectivePov 链、既有 GateService 构造 +novelData）；`mvn clean package` 通过；重启后端提示词同步 90 条、四段新文案 psql 逐条核对入库。

**实弹（书 60「POV人称探针·长篇换皮同配置-勿留」，与书 59 同配置：样本 11 + RESKIN + water=10 + 第一人称（主角）+ 预设 68 + targetChapters=5）**：
- 建书 → RESKIN 任务 101 DONE（5 章/22 场景/33 卡，~2.5 分钟）→ `POST /pipeline/run` 任务 102 跑第 1 章 → **DONE，ch1 DIGESTED 3178 字**；
- **注入实证**：`llm_call_log` scene_draft 请求含「【叙事视角（必须遵守）】+ 第一人称（主角）+ 禁止章内中途切换人称」，ai_review 请求含「【本书叙事人称】」段（psql LIKE 核对均 t）；
- **门禁实证**：章级机械报告含 `pov_consistent {"ok":true,"value":67,"baseline":1}`（叙述层「我」×67）——换皮书章级列空、走书级回退的路径走通；
- **审校实证**：两轮收口（round1 blocker 契牌连续性 → round2 minor），summary 明写「**第一人称主线推进顺畅**」；issues 类型分布 continuity/typo/logic，**0 条 pov**（正文人称全程一致，第 5 查没得报＝正确行为）；
- **正文质验**（var/ch60-1.txt）：全章第一人称无一处漂移，解契→还命→离场的情绪线完整，对白密度高、无 AI 腔黑名单命中；
- **导出坑顺带记录**：`psql COPY TO STDOUT` 会把换行转成字面 `\n`，看正文要查库 `position(E'\n' in full_text)` 或用 `\copy`，别拿 stdout 转义当数据问题。

**边界（本轮未做）**：
1. `pov_check_block=1`（开拦截）未实测——默认只报，误报率观察够了再开；
2. 多视角轮换书的「章纲定每章人称」链路单测锁了 arity 与解析，但**没有真实多视角书实弹**（需要一本配「多视角轮换」的书跑 2 章以上）；
3. 换皮/迁移链不写 `chapters.pov`（`materializeScenes` 传 null）——依赖书级回退，单视角够用；若将来换皮要逐章换人称，需在 `ReskinService.rewriteChapter` 补写；
4. 书 60 是探针（标题含「勿留」），验证完毕可删；书 59 的存量章 pov 仍为 NULL（回退路径同样生效，不必回填）。

**续跑 2–5 章（同日，任务 103–106，4 次入队：103 跑到 ch3 停、104 重跑 ch3 又停、105 过 ch3 卡 ch4、106 过 ch4/ch5）**：
- **终态：ch1/3/4/5 DIGESTED（3178/3364/3249/3056 字）+ ch2 PENDING_APPROVAL**。全书 152 次调用 / 157 万 tokens；章级机械门禁 50 轮。
- **人称防线实战命中（本轮最有价值的证据）**：ch2 初稿**真的漂了**——机械 `pov_consistent value=0 ok=false`（叙述层无「我」，按设计只报不拦如实入报告）→ AI 审校第 5 查 round1 判 **blocker**（原话「本章通篇为第三人称叙述，与配置的第一人称不符，属整章级人称漂移」）→ 修订轮凭事实基准里的人称行改回，复检 `value 0→10 ok=true`；round2 另有时序硬伤两轮未清转人工。**漂移被抓住并修回，只剩另一类硬伤待人审——三层各司其职。** ch1/3/4/5 全程 `value 59–68 ok=true`，审校 summary 逐章明写「人称统一/第一人称统一」，pov 类 issue 0 条。
- **与人称无关的卡边（暴露的是既有机械门禁的随机性，非本轮改动引入）**：ch3 两轮死在章长（2383/2377 vs 下限 2385，**差 2–8 个字**）+ 行均长两头翻（36 超上限 → 修短到 15.65 又破下限 16.1），第三次重跑才过；ch4 死在场景4对话密度 30.44 vs 上限 30.2（差 0.24），重跑一次过。RESKIN 书场景全 PASSED 不重写，拼章底稿固定，全靠章级修订轮拉长度/压密度——**卡边时自愈预算（2 轮修订+1 次重试）容易耗尽**，断点重跑即给新随机数，4 次任务全部靠既有断点续跑收口，未动任何数据。这类问题的根治属 STATUS 顶部「第三类判据」（阈值带宽），不在本轮范围。
- ch2 待审批是审校时序硬伤（潜航舰出发时序颠倒），与人称无关；探针书审批与否留给用户。

## 问题清单与处置计划（2026-10-05 盘点，**同日已按序做完前五项**，见本节末「执行结果」）

跑完书 56/57 两本换皮衍生后盘点，现存问题分五类。**分类的用处是判断「哪些能靠软件层封装重构 + 加表解决」**——结论是前两类能、后三类不能。

**基座现状（决定解法的两条）**：①「把行为变成开关」的基础设施已有——`tuning`（全局键值）+ `style_packs.gate_config`（书级覆盖），读法是 `gateService.configValue(novelId, key, tuning.d(key, def))`；②`world_states.state` 与 `material_cards.aliases` 都是 **jsonb 整块——能存不能查、不能校验**，这是第一类问题的根。

| 类 | 根因 | 典型问题 | 解药 |
|---|---|---|---|
| 一、状态账本缺失 | 跨章事实只有自由文本，无可校验的结构化账本 | 跨章时间/衣着/年龄漂移；术语两称（纹服/纹衣）；正文出现世界设定外的实体（河/桥） | **加表** |
| 二、流程时序缺陷 | 改了数据后面不重新校验、不回退；两种语义耦合在一个入口 | 机械门禁对成品失效（9/13 章在带外）；**打回毁迁入剧情**；复沓「修一轮就采纳」越修越多 | **纯重构** |
| 三、判据不可靠 | 判据靠模型主观判断，无客观基准 | 复沓零容忍口径（实测人类出版原文同样触发）；读者评审 hook/stakes | 只能换客观判据，根治不能 |
| 四、产品定调 | 没有技术正确答案 | 成品与门禁冲突谁优先；复沓阈值；迁移模式下外衣「像到什么程度算违规」 | 只给开关（基座已就绪） |
| 五、规模成本 | 物理约束 | 长书线性放大（**单章 10.5 分钟 / 22 万 tokens**）；多用户并发 | 只能改善效率 |

**第一类的三张表**（加表 = 把隐式知识显式化，不依赖模型变聪明）：
- `character_states`（书 × 章 × 人物）：时间锚/所在地/**衣着**/随身物/身体标记/称谓，全部独立列。有它才能做**出稿后一致性校验器**——书 57 第 8 章「袍子不该存在」、书 56 第 9 章「孩子年龄」、第 13 章「额心/眉骨」都归它管。
- `entity_aliases`（书 × 实体 × 别名）：别名现在是 jsonb 数组，**不可反查**；独立表 + 唯一约束才能在生成前归一、生成后检出「纹服/纹衣」。
- `sample_chunks`（或给 `embeddings` 补 SAMPLE 行）：样本原文不入库 ⇒ 事后「照抄原文」全文查重在现有模型下做不到。

**第二类的三处重构**（改控制流，不动数据模型，投入最小）：
- `rejectReset` 拆语义：「打回正文」（保章纲/场景，重跑复用迁入剧情）vs「打回章纲」（才清场景）。现状一个入口全删，是书 56 第 3 章 7 拍被重编成 3 场的直接原因。
- 成品终检关：评审修订后统一重过机械门禁，按配置策略处置（回退/再修订/放行并标记）。
- 复沓修订**取优不取新**：新版重复率更低才采纳（实测 2 章越修越多）。

**第五类的一个硬事实**：正文生成**不能并行**——事实账是链式依赖（第 N 章的 digest 就是第 N+1 章的上下文）。所以 200 章就是约 35 小时，压不下去，只能靠提高一次通过率少烧钱（书 57 第 3 章单章烧 39.5 万 tokens / 31 次调用，是均值的 1.8 倍）。

**长书可行性（同日实测）**：库里最大样本「未解析长样本 4,239,902 字 / 1545 块」**从没解析过**（`sample_parse_tasks` 只有深海系 23 万字 FULL DONE、源主角 3.9 万字 FAST DONE 两条），所以长书链路未验证。已跑过衍生的最大篇幅＝源主角 13 章。成本外推：13 章 ≈ 2.3 小时 / 300 万 tokens / 8.3 元；77 章 ≈ 14 小时 / 49 元；200 章 ≈ 35 小时 / 127 元；1000 章 ≈ 7 天 / 637 元（空闲价，高峰 14-18 点翻倍）。**建议先拿已解析好的深海系（23 万字 / 77 章）跑一本做中量级验证。**

**排序建议**：先做第二类（半天量级，修的是数据损坏）→ 再做 `character_states` + 一致性校验器（收益最大的一张表）→ 然后 `entity_aliases` → 最后把第三、四类做成配置开关交用户调。

### 执行结果（2026-10-05，分支 `10/1005-一致性加固与状态账本`，8 个提交，未 push）

| 计划项 | 落点 | 关键提交 |
|---|---|---|
| 第二类-1 打回拆语义 | `resetForTextReject` / `resetForOutlineReject` + 场景侧意见注入 | `4b1f811` |
| 第二类-2 成品终检关 | `GateService.evaluateChapter` + `gate_recheck_action`（V38） | `60fcaf1` |
| 第二类-3 复沓取优 | `ReviewService.repeatFixBetter` + 真实输出夹具回放 | `b37afa6` |
| 第一类-1 `character_states` | V39 表 + 投影/回填/核对 + 三个端点 | `68a32f9` |
| 第一类-2 `entity_aliases` | V40 表 + 归一化（账名字与卡名对齐） | `8e38d43` |
| 第三、四类 配置开关 | V41 + `reader_repeat_fix_min`/`reader_structural_block` | `a3d7d25` |
| 契约收口 | `docs/architecture/pipeline-contracts.md §十`（10.1–10.6） | `71bc7b7` |
| 附带修掉 | ①导出加字段打挂生成链的回归（`ChapterTextRow` 拆两型，`b908202`）②注水降级只写 note 不改 verdict（从未生效，`a3d7d25`） | |

**验证口径**：`mvn test` 297/297；逐提交编译核对；实弹＝打回后重跑书 56 第 8 章（章纲 997 字原样保留、6 个场景蓝图全留、
0 条 OUTLINE 步骤行、llm_call_log 里两条 scene_draft 请求含注入的打回意见、整章走完 789s）、书 56 第 9 章整章（623s）、
V38/V39/V40/V41 逐个应用、人物账回填 99+86 行并与源快照逐字对齐、别名反查与归一化在真库生效、受控探针验核对规则。
**副作用**：书 56 第 8、9 章被这两次实弹重写并（auto 模式）转 DIGESTED（原为待人工审批）。

**本批未做（留给下一轮）**：
1. `sample_chunks`（或给 `embeddings` 补 SAMPLE 行）——样本原文不入库，「照抄原文」全文查重做不到，第一类的第三张表；
2. 迁移/换皮模式下的**外衣相似度**检查（「像到什么程度算违规」是第四类定调，但连判据都还没有）；
3. 浏览器走查（本机通道不可用，全局规则也禁用可见面板）——本批所有 UI 改动（打回文案、导出按钮）都只做了接口实弹；
4. 中量级长书验证（深海系 23 万字 / 77 章）——那是「能不能用更长的原小说跑衍生」的答案。

## 长源换皮 5 章测试（2026-10-05 晚，书 59「长篇换皮测试·五章」，未提交）

**目的**：用户导入 122 万字长篇《样本11》（样本 11，912,381 汉字 / 505 章 / 339 语料块），要求先跑 5 章看效果（模式 RESKIN、预设 68、targetChapters=5、第一人称主角、water=10）。

**执行**：样本 FAST 解析（任务 4，44 单元 / ~5 分钟 / 40 章节点 + 1 书节点 + 108 卡）→ 建书 59 → RESKIN 任务 99 自动入队（~9 分钟：1 份换皮设定 + 5 章目标改写 + 22 场景 + 31 卡）→ `POST /api/pipeline/run` 1–5 章，任务 100 **DONE（4 章，~50 分钟）**。

**结果**：ch2/ch3/ch4 **DIGESTED**（2754 / 3021 / 2736 字），ch1 与 ch5 停在 **PENDING_APPROVAL**——AI 审校两轮 BLOCKER 未清，即使 `approvalMode=auto` 也按规约转人工。两章的硬伤是**同章内的物品/时间线记账**：ch1「打捞装备箱里是面罩+撬具，前文写的是隔离服+登记藻管，且藻管位置三处打架」+「共生契约三年 vs 还剩七个月零九天」；ch5「账册被周牧收走，陆青禾却就地摊开翻整本」+「壳子下午才试躺，后文说前天」。**判定这是系统正常工作**（拒绝把有硬伤稿自动放行）。
**换皮效果**：源为都市豪门（样本11人物甲/样本11人物乙/样本11人物丙/样本11人物丁），新外衣为**星际打捞**（陆青禾/沈拾/谢无咎/宋漪/郗临/周牧；解契书/睡眠茧/记忆藻/舰长伴侣舱/冷泉矿区/土星环带）。正文（var/ch59.txt）第一人称冷冽腔、情节骨架忠于原章，外衣置换干净——**没有出现源书专名残留**（此前「防照抄」的担心在这一本上未复现）。
**成本实尺**：131 次调用 / 1,288,572 tokens（in 445,145 / out 843,427）≈ **¥7.15**（13:41–15:2x 跨高峰，其中 ¥6.65 落在 14–18 点倍率档；同样跑法放空闲档 ≈¥3.6）。按节点拆：scene_draft 25 次 38.9 万、scene_revise 30 次 30.8 万（**重写多于初稿**）、ai_review 8 次 19.2 万、chapter_revise 11 次 14.4 万。→ 单章 ≈1.4 元（高峰）/ ≈0.7 元（空闲），**与「单章 22 万 tokens」的旧估一致**。

**本轮暴露并修掉的两个真缺陷**：
1. **样本切章把前言切成一章**（`SampleParseService.splitChapters` 与 `NovelService.splitChapters` 口径不一）⇒ 书 58 首章＝整理者声明、真第 1 章被挤到 seq=2、**整本换皮映射错位一章**。已对齐（前言并入首章）+ `preambleMergesIntoFirstChapter` 测试；用真实 122 万字体重新解析核对 seq=1＝「第1章 被送给陌生男人」。
2. **`resultType="boolean"` 配 `SELECT COUNT(*)`**：`MaterialCardMapper.hasCards` 数整本书的卡（31 张）→ `Cannot cast to boolean: "31"` → 新写的 `EntityAliasService.ensureIndexed` 每次抛异常被 fail-open 吞掉，**人物状态账每章都投影失败、账恒为空、核对恒无发现（静默功能全损）**。全库 8 处同类（CanonDoc/Digest/Foreshadow×2/LlmNodeConfig/MaterialCard×3）一并改 `EXISTS`；重启后实证：别名索引 43 条/31 卡、人物账回填 3 章/20 行、`/audit` 空、日志 0 次 cast 报错。`mvn test` 300/300。详见 AGENTS 坑 27/28。

**观察（未处置）**：换皮设定里「沈拾＝陆青禾失忆期旧身份」这层一人两名的关系没进素材卡别名，所以人物账上仍是两行（归一化只认卡里声明过的别名）。另：转人工的两章我没动——审批是产品决定。

## 书籍管理加「导出」：全书正文导出 txt（2026-10-05，未提交）

- 后端 `GET /api/novels/{id}/export`（`NovelController`）：`NovelService.exportText` 读 `chapterData.listTextsByNovel`（**只装有正文的章**、按章号升序），每章拼「第N章 [标题]」独立一行 + 空行 + 正文 + 空行；返回 `text/plain;charset=UTF-8` + `Content-Disposition: attachment; filename*=UTF-8''…`（RFC 5987，中文文件名不乱码）。无正文可导出时报业务错 A0006 带人话（「还没有正文可导出——先去规划页或工作台生成」）。
- **刻意不加书名/简介抬头**：抬头会被「按行首标题切章」的导入器吃进第 1 章正文，导出稿要能直接再导入回来（书名已经在文件名里）。
- **拼章题不去重会出洋相**：迁移/换皮建的书 `chapters.title` 为 NULL（不伪造章题）→ 回退「第N章」；导入书的 title 往往**就是**原行首标题（「第一章 猫的落户」）→ 原样用，否则拼出「第1章 第一章 猫的落户」这种导出稿连自己都导不回来。判据见 `NovelService.chapterHeading`（阿拉伯与中文序数都认）。
- 读模型改动：`ChapterDataService.ChapterTextRow` 增 `title` 字段 + `ChapterMapper.listTextsByNovel` 多取一列（`BookFingerprintService` / `GenrePresetService` 两个既有调用方只读 chapterNo/fullText，不受影响）；测试里两处构造器同步。
- 前端 `BooksView.vue`：操作列加「导出」按钮（列宽 300→340）。因 `api.js` 的封装只认 JSON，这里走**原始 fetch** 取二进制，并按 content-type 分流——失败时后端回的是 Result JSON，不能当 txt 存下来（否则得到一个打不开的「文件」）。
- **实弹**：书 57 导出 200 / `text/plain;charset=UTF-8` / 113,581 字节 / 13 章 38,697 字，章题与正文格式正确；无正文的书回 A0006 人话；有标题的导入书（书 2）章题走 title 分支不重复。`mvn test` **251/251**，前端 `npm run build` 通过。
- **顺带发现（未处理）**：导入书的正文里带着源文件的 markdown 标题（书 2 第 1 章正文以「# 导入书A / ## 第一章 猫的落户」开头），导出如实带出。属导入侧既有问题，不在本次范围。

## 修「章题行进成品」：剥离不再依赖 chapters.title（2026-10-04 深夜，未提交）

**现象**：书 56 第 3/4/12 章、书 57 第 3 章的正文首行就是「第 3 章」这种题行（书 57 写的是「第3章」，无空格）。

**根因是四步链，比初看深一层**：①源主角样本的章题本身是「第N章（无标题）」——样本没有真章名；②`NovelService.cleanNodeTitle` **刻意**把这种标题归一成 `null`（注释：「无真标题时不伪造章题」）；③于是迁移/换皮建的章行 `title` 恒为 NULL；④`stripTitleLine` 第一行就是 `title == null → return fullText`——**等值匹配这条路径对迁移类书是死代码**，模型写在首行的题行永远剥不掉。

**修法两层**：
- **确定性剥离（主修）**：`stripTitleLine` 去掉 `title` 前置返回，加形态判据——**整行本身就是个章题**才剥，两种形态：纯章题行（`第3章` / `第3章。`），或章序号后由空格/冒号引出的短标题（`第3章 归墟` / `第三章：断口`，标题 ≤12 字且不含句读）。**第一版正则写宽了**，把「第三章的门在右边。」也剥了——`ChapterTitleStripTest` 当场抓到，改成两形态后过。
- **提示词预防（配套）**：场景提示词红线加一条「正文里不得出现章题行或章序号——章题由系统统一处理」。剥是兜底，别让模型写了再剥。

**证据**：`mvn test` **248/248**（新增 `ChapterTitleStripTest` 4 条：title 为 null 也剥、带标点/冒号/空格形态、等值路径仍工作、**不误剥正文**）。改动：`ChapterPipelineService.stripTitleLine`、`PromptCatalog` 场景红线一行、新增测试。

**生效前提**：需重启后端。当时书 57 的正文任务还在跑，**没有重启**，所以该轮后续章节仍可能出现题行；跑完重启即修复。书 56 已落库的三章（3/4/12）要清掉存量题行，得单独跑一次正文清洗（尚未做）。

**另一条独立的可选改进（未做）**：给换皮章生成真章名写入 `chapters.title`，让 UI/导出有目录。注意这**不修本题**——模型写的是「第3章」而不是生成的真章名，等值匹配照样不成立。

## 换皮收尾补一步「设定卡补全」：素材层从 6 张人物卡 → 40 张覆盖 6 类（2026-10-04 深夜，未提交）

**起因**：核查「衍生过程中到底生成了哪些素材、有没有校验」时发现，换皮书的素材层只有 `bindCharacters` 落的人名表——书 56 是 **6 张卡、全是 character、aliases 全空**。而素材卡是设定层的注入来源（场景按 `pinned` + 别名命中注入），地点/器物/组织/现象一律无卡 ⇒ 注入形同虚设。这正是正文里「第 7 章铜片凭空出现在怀里」「第 5 章同一件衣服叫纹服又叫纹衣」「第 13 章同一处伤叫额心又叫眉骨」的机制性原因。

**改法（复用既有链路，不另起一套）**：`ReskinService.run` 的逐章换皮跑完后加第三步，调 `BookAssetExtractService.extractCards(novelId, overwrite=true)`——就是导入解析链 CARDS 步用的那条（`BOOK_CARDS` 节点，kind 枚举含 location/item/org/phenomenon/landmark/disaster，产出带 aliases）。两个关键取舍：
- **必须 overwrite=true**：不覆盖则换皮设定落的 6 张人物卡永远补不上别名。
- **fail-open**：补卡失败只 `log.warn`，不连累换皮结果（卡不全顶多注入弱一点，不该让整批换皮判失败）。`ReskinResult` 增 `cards` 字段，任务消息带上张数。

**实弹（书 56，走现成的 `POST /api/novels/56/import-analyze` steps=[CARDS]）**：`模型给出 40 张，新增 34 张、覆盖 6 张`；库里从 6 张 → **40 张覆盖 6 类**：地点 6（灯城/灯藻园/热流峡/峡口/无光冰原/听歌亭）、物品 8（律纹/刻录鞋/索缆滑斗/脉券/脉盘/铜片/纹服/空白灯牌）、组织 5（灯脉世家/熄灯人/脉流商会/无纹者/乞食队伍）、现象 9（灯脉/刻录/止息/闭感静默/一线长鸣/不可言说者…）、灾害 1、人物 11（新增沈砚父亲/沈砚祖父/老熄灯人/小沈砚/北来客）。**32/40 张带别名**，其中「纹服」的 aliases 直接记着 `纹衣`——术语不一致在设定层就被收掉。

**证据**：`mvn test` **244/244**（新增 `ReskinServiceTest` 2 条：换皮跑完确实调补全且用 overwrite；补卡抛异常时换皮结果不受影响）；`mvn compile` 通过。改动：`ReskinService`（注入 `BookAssetExtractService` + 第三步 + `ReskinResult.cards`）、`GenerationQueueService`（任务消息带卡数）、契约写入 `pipeline-contracts §五`。

**仍未做**：素材层/大纲层/世界观层的**相似度与合规校验仍为零**（MIGRATE/RESKIN 下 `derive_originality` 与 `derive_no_copy` 按设计全跳过）；正文里出现世界设定与素材卡都没有的实体（如第 4 章「河」「桥」）仍无人拦；样本原文没有入库，事后做「照抄原文」全文查重在现有数据模型下做不到。详见本轮对话的评估清单。

## 换皮书 56 跑满 13 章 + 连续性闸实弹；顺带揪出「时间跨度不进账」这个根子（2026-10-04 晚，未提交）

### 一、实跑结果（书 56《系统测试书甲》，源主角样本）

任务 92（`CHAPTERS 3..13`）**DONE**，13 章全部有正文、**合计 39,961 字**：**11 章 DIGESTED（自动过闸）/ 2 章 PENDING_APPROVAL（第 8、9 章，审校硬伤两轮未清按设计转人工）**。**样本专有名词残留逐章扫描 0 处**（`var/scan_sample_nouns.py`：源主角/源配角甲/婆罗门/沙门/吠陀/梵/佛陀/源配角乙/源配角丙… 全零）。

按章的闸门轨迹（`gate_reports`）：第 7 章机械闸失败 12 次、第 8 章 17 次（`dialogue_end_punct_ratio=0.0（基线0.5）` 那类场景级重试），最终都靠自愈梯子收口。

### 二、两条新闸实弹确认生效

- **`AI_REVIEW_REVISE` 的【事实基准】**：`llm_call_log` id 2121（第 3 章修订）请求体里命中 `【事实基准（改稿必须与它一致）】`，内容含「时间 / 逐人位置 / 随身物品 / 新承诺 / 未解」五块。此前修订轮拿不到审校器看到的状态，是「连续性 BLOCKER 修不掉」的直接原因。
- **复沓升硬闸**：第 3 章 `reader_review` 判 `verdict=pass` 但 `repeat` 列了 **5 条**，随即触发 `reader_fix`（正常路径只在非 pass 时才修）→ 二轮评审。全 13 章里 **9 章**触发过这轮去复沓修订——此前 `repeat` 被 `readerFix` 消费却从不触发，是死参数。

### 三、顺手揪出的两个真根子（都已修，其一尚未实弹）

1. **世界状态的「时间」是错抽的（已修 + 已实弹）**。第 3 章曾判 BLOCKER：正文写「三年前出城」「三年里」，基准却写「过峡后约半月」。核到源头——**换皮设定与大纲都写「三年间」、ch2 正文也写「三年」，只有 digest 抽出的 `world_states.state->>'time'` 是「半月」**（模型从「这半月掉的肉」这种局部身体描写反推了总历时）。于是审校器拿错基准去判正确正文。修法：`PromptCatalog` 的 `digest/state_spec` 给 `time` 字段加防呆（只认正文明确写出的时间线索；局部时长严禁反推总历时；无线索就沿用上一章表述）+ 手工订正 ch2 那行错值。**实弹证据**：重跑后第 4 章的世界状态直接写成「正文未给出明确日期推进，仅有『走了不知多久』等局部模糊时长」——模型按新规矩拒绝编造了；第 3 章的「半月」矛盾消失，本章直接 DIGESTED。
2. **换皮章从来不写 `time_note`（已修，未实弹）**。ch8/ch9 的 `chapters.time_note` 全是 NULL。`time_note` 是 digest 时间锚点段的唯一来源，缺它就只剩「靠抽」——而样本的章格动辄跨年（源主角跨几十年），换皮时被压成「紧接上一章／次日」，于是**第 9 章设定把「偕幼子朝觐／苏眠之死／父子因缘」压进一章，正文写出的孩子已经会喊爹，而按大纲孩子要在灯城篇末尾才出生**，审校器判 BLOCKER 且两轮修不掉。修法：`derive_reskin/chapter` 输出结构加 `time_note`（并要求「原章跨几年新章也跨几年，严禁把岁月压成次日」）→ `ReskinService.rewriteChapter` 落 `chapters.time_note`。另加 `ReskinPromptArityTest`（3 条）把「模板 %s 个数 == 调用点实参数」钉死——`formatSafe` 是 fail-open 的，个数不匹配会**静默回退代码模板**且不报错，这条契约必须有测试兜。

### 四、仍欠的两项

- **上面第 2 条没有实弹**：`time_note` 只在**新跑换皮**时才产生，书 56 存量章行仍是 NULL。验证要重跑一次换皮（全书 13 章设定重生成 + 13 章正文重生成，约 2 小时），且会覆盖现在这份已验证的成果——**要不要跑、以及若另起一本测试书叫什么名，请用户定**（题名归用户，见 2026-10-03 那次教训）。
- **场景级「外衣漂移」**：第 8 章的 BLOCKER 是同一场景里衣着自相矛盾（章级设定两处都写「灯纱外袍」，正文某场景写成「灰布短衣」）。根因在**场景写手自由发挥**，不在设定层。可选修法：把换皮设定的 `characters` 表连同衣着一起落进素材卡，场景按 pinned 注入，给模型一个可见的衣着基准。

### 五、证据链

`mvn test` **242/242**（新增 `ReskinPromptArityTest` 3 条）；`mvn -o compile` 通过；重启后端提示词同步 89 条（`prompt_templates` 里 `derive_reskin/chapter` 已含 `time_note`，`digest/state_spec` 299 字新口径）；实跑数据全部落在 `llm_call_log` / `gate_reports` / `world_states`。改动文件：`PromptCatalog`（state_spec 时间防呆、AI_REVIEW_REVISE 基准优先级、derive_reskin/chapter 加 time_note）、`ReskinService`（写 time_note + `SAMPLE_TIME_HINT`）、新增 `ReskinPromptArityTest`。

## 新增第三种模式「剧情换皮」RESKIN：保骨架、外衣全随机（2026-10-04，用户定调，未提交）

> **补充（同日，实跑后加强）**：用户重申需求口径「**只复刻剧情，其他东西全都要换成衍生书对应的东西**」。为此给换皮设定加了 **`characters` 人物名表**（3-6 个），三处生效：①落成 `material_cards(kind=character)`（场景按别名/常驻注入，同时给「不得新造人名」一个可见来源）；②**回填 `derive_config.povCharacter`**（用户未显式指定时）——否则场景提示词的视角人物仍是「（未指定）」，模型会自由发挥人名；③逐章换皮提示词明令「人名只许用 characters 里的名字，不得新造、不得残留样本人名」。另修 `runReskinTask` 进度上报差 1（13 章跑完显示 12）。
> **实跑（书 56《系统测试书甲》，源主角样本 13 章）**：换皮 DONE「13 章 / 74 个场景」，样本专有名词在章行与场景里残留 **0 处**。换出来的映射相当准——`源主角(婆罗门之子)→沈砚(灯脉世家独子)`、`源配角甲(挚友)→陆朴(同门挚友)`、`佛陀/沙门→明岑(无纹行者)`、`源配角乙(名妓)→苏眠(灯城歌者)`、`源配角丁(商人)→石万川(商会首富)`、**`源配角丙(渡口船夫)→陶渡(热流峡引缆人)`**（连"摆渡"的功能位都换成了缆索渡）。世界观＝潮汐锁定行星永夜面 + 脉流灯城 + 刻录技艺 + 禁「暗走」。

- **需求原话**：「再加一套生成衍生剧情具体情况的步骤，系统可以随机生成剧情复刻但其它全部都随机重新生成的效果」。用户给的对照例：`马力去商船打工→遇船长→船长想捕捞海怪→找船员与赞助商人→人齐了→整船人被海关全灭` ⇒ `林枫去便利店打工→遇店长→店长说便利店闹鬼→邀他猎魔→用人脉凑齐人→最后被鬼怪全灭`。
- **口径**：`derive_config.mode` 由两值扩为三值——`ORIGINAL`（默认）/ `MIGRATE`（原样迁移）/ **`RESKIN`（换皮）**。`migrate()` 对 MIGRATE/RESKIN 同时为真（防复刻三闸、自愈不换目标、卷规划守卫这些「按样本剧情走」的退让全部自动继承），`reskin()` 只对 RESKIN 为真。
- **换皮做什么**（`ReskinService`，两步）：①**定一套全书共用的新外衣**（题材/世界/主角/配角风格/新全书大纲，`derive_reskin/skin`，随机种子取时间戳尾数，温度 1.0）——**必须先定一次再逐章用**，否则每章各换各的会拼不成一本书；设定落 `canon_docs(kind=misc,name=换皮设定)` 留档可复查，同时把新大纲/新世界观写进 canon（覆盖位置与常规书一致）。②**逐章换皮**（`derive_reskin/chapter`）：节拍数量/顺序/每拍功能/结局形状一比一保留，人名地名组织名职业器物生物全部换成本世界对应物，输出写回 `chapters.goal/hook` 并**复用 `OutlineService.materializeScenes` 物化场景**（与 MIGRATE 同一条写路径）。
- **为什么是队列任务**：换皮要 1 次设定 + 每章 1 次 LLM，十几章就是十几分钟，同步跑会把建书请求挂死。新增 `TaskKind.RESKIN`（`generation_tasks.kind` 无 CHECK 约束，故无需迁移），由 `NovelController.create` 在**事务提交后**入队（事务内入队会撞「worker 读不到未提交章行」这个已知坑）。逐章 **fail-fast**：设定定了却只换一半的书，前后外衣不一致，比整批失败更难收拾。
- **RESKIN 下不克隆样本的任何外衣**：素材卡/世界观/大纲（那些全是旧外衣）一概跳过，只按样本建**章行结构**（`clonePlotChapters(..., withScenes=false)`，不预物化场景——管线此时若被启动会退回 AI 章纲，不会误用旧外衣）。`protagonistFrom` 换名在 RESKIN 下不适用（人名由换皮设定生成）。
- **证据**：`mvn test` **239/239**（`DeriveSupportTest` 新增 RESKIN 归一化与 `migrate()/reskin()` 正交 1 条）；前端 `npm run build` 通过；**实弹**（源主角样本、3 章探针，跑完即删）：建书即自动入队 `RESKIN` 任务 → DONE「换皮完成：3 章 / 18 个场景」，4 次 `derive_reskin` 共 21,254 tokens（≈¥0.1）。**换皮效果质验**：题材 古印度宗教 → **科幻·轨道打捞·后人类精神寓言**；主角 源主角 → **周衡**，挚友 源配角甲 → **宋砚**，沙门 → **零压行者**，吠陀 → **《设定内典籍》**，唵字/大梵 → **内核基频/真我**，榕树 → **水培榕**，离城 → **离舱**；而第 1 章节拍仍是**同样 5 拍、同样顺序与功能**（习得智慧被爱戴 → 树下冥想求解脱 → 寻新路 → 请父亲许可 → 黎明诀别）。探针书 55 已物理删除。
- **同一批跑出来的运维事实**：书 54《系统测试书乙》第 3 章连败停止，真因是 **LLM 账户余额不足**（HTTP 402 `Insufficient Balance`，见 `llm_call_log` id 2015/2017/2019/2021），不是代码问题；**且日志印证迁移守卫按预期生效**（「本书为剧情迁移模式，跳过 replan（迁入剧情不换），转人工」）。该书已完成 2 章、可断点续跑。另一处遗留：`chapter_steps` 里把这次失败记成 `INTERRUPTED / 用户终止（硬中断）`，与实际 402 不符（既有标注问题，未修）。

## 新增「剧情迁移」模式：样本逐章剧情直灌本书（2026-10-03，用户定调「最重要的就是剧情批量迁移」，未提交）

- **起因**：用户在向导页看到「剧情骨架预填大纲（慎用：…建议改用「AI 生成大纲」）」的告警，质问「写这个系统就是为了快速批量迁移爆款剧情，你搞个警告那还能不能用了」。核查后确认：**不是文案问题，是产品定调冲突**——系统为防复述刻意装了三道闸，与「迁移剧情」方向相反。
- **三道闸（改前现状）**：①`cloneSampleAssets` 跳过 `kind=character`（人物卡不克隆）；②`activate` 见大纲仍以 `> 由样本《` 开头即 409；③`ensureOriginality`（`derive_originality` 节点）只要选了样本就跑，判复刻重写 ≤2 轮、仍复刻任务 FAILED。
- **另一个更硬的缺口（这才是「迁不动剧情」的根子）**：`cloneOutline` 只用**书级**剧情节点（一段骨架），样本的**章级**剧情节点（源主角 13 条 / 深海系 77 条，每条含 `beats=[{goal,outcome,conflict}]` 节拍表）**根本没进新书**——所以「迁移」此前只是迁了个大纲壳。
- **本次实现（做成 opt-in 模式，原创衍生路径零改动）**：
  - `derive_config.mode`：`ORIGINAL`（默认）/ `MIGRATE`；解析归一化在 `DeriveSupport.normalizeMode`，**缺省/未知/坏 JSON 一律 ORIGINAL**（防复刻防线不因坏配置静默关掉）。
  - 迁移口径：人物卡照常克隆；骨架大纲原样作本书大纲；skip 复刻审校；**章级剧情节点 → 本书章行**（`chapter_no` 重排、`volume_no` 按 `chaptersPerVolume` 切卷、`arc=样本名·迁移卷N`、`goal=章概要`、`hook=末条 beat outcome`）；**beats → 场景拆解预物化**（`OutlineService.materializeScenes` 复用章纲既有落库写路径，写 `outline_yaml` + `chapter_scenes`）。
  - **生成管线因此天然接住**：`ChapterPipelineService.outlineStep` 见场景已存在即 DONE（原本就是断点续跑设计），迁移剧情成为该章唯一方向约束——不再烧一次 AI 章纲，也不会被自由发挥带偏。
  - 章数上限＝`targetChapters`（「10 万字以内」这类约束靠它兜住）；场景字数＝章预算下限按 beats 条数摊分（300–1500 字/场夹紧）。
  - **卷规划守卫（防销毁）**：`VolumePlanService.adopt` 会删 from 起整卷规划行（只拦「已有正文」），迁移书一点「AI 规划一卷」就会把迁入的章连场景一起抹掉。两道拦截：入队口 `GenerationQueueService.assertMigratedNotOverwritten`（**花钱之前**就 STATE_CONFLICT）+ adopt 内同条件兜底。向导完成页也改成迁移模式直接去工作台生成、明写「别点卷规划」。
- **向导 UI**：「开书模式」单选（原创衍生 / 剧情迁移）；切模式联动克隆勾选（迁移＝全开且剧情骨架锁死）；文案改为说清代价，不再用「慎用」；完成页按模式给不同三步指引。
- **证据**：`mvn test` **237/237**（新增 `DeriveSupportTest` 模式归一化 1 条、`NovelServiceMigrateTest` beats→场景映射 4 条）；前端 `npm run build` 通过；重启后实弹三项——①迁移探针书（源主角、targetChapters=3）**9 张卡（含 4 张人物卡）+ 3 章章行 + 18 场景预物化**，activate 放行（`mode=MIGRATE`）；②跑第 1 章：`llm_call_log` **无 `outline` 节点**（章纲步确实被跳过），6 次 `scene_draft` + 3 次 `scene_revise` → 成稿 2929 字 `DIGESTED`，digest/world_state/foreshadow/embedding 四层均有真实行，正文内容即样本第 1 章（婆罗门之子/源配角甲/祭火咏诵/对教义的怀疑）；③守卫实弹：迁移书点卷规划立即被拒（队列零 PLAN 任务）；原创探针书（ORIGINAL）卷规划仍被接受（提交后即停止，未误伤）。**三本探针（47/48/49）已全部物理删除，读数回到开工态**。
- **契约同步**：`docs/architecture/pipeline-contracts.md §五` 新增「剧情迁移模式契约」对照表 + 守卫 + 合规口径；AGENTS.md 铁律与爆款链段落同步。
- **明确不做的**：不提供任何自动规避查重/改写的"反检测"能力；向导文案明写产出与样本实质性相似、请在授权范围内使用。样本只用自己有权处理的稿源。
- **【同日补，实跑《系统测试书乙》暴露的三处】第一次真跑 13 章时第 1 章走样，根因三处，全部已修**：
  1. **`derive_redline` 在迁移模式下反向注入**（`ContextPackerService.deriveSection`）：这段「衍生差异红线」写的是「禁止复述样本原书的情节走向、主角必须原创」——只要选了样本就注入，**与迁移完全对立**，是缩略版"第四道闸"且此前没被注意到。修法：新增 `derive_migrate` 段（`PromptCatalog`，MIGRATE 专用）替换之，二选一注入。迁移段口径：按章纲推进不另编情节 + 章纲里的样本主角名统一按本书主角 `{povCharacter}` 写、配角沿用原名（顺手解决「设了主角林峰却写成源主角」的换名问题，实跑第 1 章正是第三人称源主角）。
  2. **失败自愈梯子的 replan 会抹掉迁移剧情**（`ChapterPipelineService.runChapterWithHeal` / `reviewBlockedDisposition` → `VolumePlanService.replanChapter` 内 `resetForReoutline` 删场景+清章纲→AI 重编）：实跑日志实锤——第 1 章 5 个迁移场景全 PASSED、成稿 2996 字，复审 BLOCKER 触发 replan 后**场景清零、AI 重出 3 个场景、goal 被改写**，迁移剧情整章丢失。修法：MIGRATE 书**一律不换目标**（两处调用点都加 `cfg.migrate()` 早退，emit 事件 + warn），失败/硬伤转人工，保留迁入章纲。
  3. 上一轮已修的卷规划守卫不动。
  重跑旧书第 51 本验证（原第 50 本因第 1 章已被 replan 弄脏而删除重建）。
- **【同日再补】迁移换主角名：`derive_config.protagonistFrom`（显式，绝不自动推断）**。第 51 本实跑第 1 章是第一人称「我」但主角名 0 次命中、反被配角喊了 2 次「源主角」——只靠提示词不可靠。改成确定性替换：`protagonistFrom`＝样本里的原书主角名，迁入的章纲/goal/hook/beats 里全部换成 `povCharacter`。**为什么不做自动推断（第一版做了，是错的）**：v1 按「★2+ 人物卡里出现最多者」猜主角，结果**选中了配角「源配角甲」**，把 "源主角与挚友源配角甲一同修习" 改成了 "源主角与挚友林峰一同修习"——静默改错人名比不改更糟。根因也随之查明：**样本深度解析根本没给主角「源主角」建卡**（★2+ 只有源配角甲/源配角乙/源配角丁/小源主角），所以按卡猜必然猜歪。现口径：字段留空＝沿用样本原名；填了才换，且只换这一个名字（配角名原样保留）。向导「叙事视角」在迁移模式下多一个「样本原书主角名」输入框并写明**不做自动推断**。
- **本次实跑配置（用户按图指定）**：《系统测试书乙》＝源主角样本 + MIGRATE + water=10 + 第一人称主角「林峰」+ 原书主角名「源主角」+ 30 章/卷·目标 30 章 + 关闭无人续跑 + 优先级中；克隆勾选按图只勾剧情骨架（设定卡与世界观不勾）。实跑落库：13 章 + 74 场景预物化，换名后章行/场景里残留「源主角」**0 处**。
- **【换名要覆盖全部克隆产物，别只改章级剧情】**：第一版只换 `chapters.goal`/`hook`/`outline_yaml`/`chapter_scenes`，实跑第 1 章正文里「源主角」仍出现 5 次——**漏了书级「大纲」**（克隆来的样本骨架，`canon_docs(misc/大纲)`），而大纲是场景提示词里最显眼的名字来源，模型照抄它。现 `cloneSampleAssets` 在迁移模式下把换名统一应用到**大纲/世界观/素材卡（name+summary+content）/章级剧情**四处，判别式一次算好传下去。修复后重建实跑：大纲「婆罗门之子**林峰**出身高贵…」、13 章章行与 74 个场景里残留原名 **0**、配角名（源配角甲）原样保留；第 1 章成稿 3100 字＝**林峰 2 次 / 源主角 0 次 / 我（第一人称）106 次**，reader `pass`、审校 `minor`、digest 落库。
- **踩到的轮询假象（值得记）**：自愈梯子重试之间章状态会**瞬时置 FAILED**，按状态轮询会误判成失败（本次差点据此宣布第 1 章挂了）。要看终态得结合 `chapter_steps`（本次 attempt 1 场景 1 门禁不过 → attempt 2 全过）+ 任务 `last_message`，别只看单次状态快照。

## 软删机制整体下线：删除＝物理删除（2026-10-03，用户定调，未提交）

- **触发**：用户问「你确定软删功能是没瘫痪的状态吗」。核查结论：**写是好的、读是半瘫的**——手写 XML 都带 `is_deleted = FALSE`，但 MP 自生成的 SQL 带不了。实弹两例：`GET /api/chapters/338` 返回了已软删章节的完整正文、`GET /api/novels/42/derive-config` 返回了已软删书的配置；列表接口反而干净（novels 列表没有 42、book 1 章节列表没有 338），所以表面正常、漏点全在「按 id 取单个」的路径上（`getById` 打在软删实体上约 17 处，`NovelService.requireNovel` 这类存在性守卫对已软删 id 失效）。用户据此定调：**把软删相关的设定全部从代码层清除**。
- **代码层**：XML 152 处 `is_deleted` 读过滤 + Java 35 处 `.eq("is_deleted", false)` 删除；6 个 `softDelete*` 语句改 `DELETE FROM` 并去 soft 改名（`deletePlan`/`deleteCustom`/`deleteCard`/`deleteOrphanPack`…）；`BaseDO` 去掉 `deleteTime`；前端 6 处「软删，可恢复」的删除确认文案改口（不改就是骗用户）。
- **脚本踩的坑（值得记）**：按行删谓词时，遇到「`WHERE is_deleted = FALSE` 是语句里唯一 WHERE、后面跟着 `AND (...)`」会删出缺 WHERE 的破碎 SQL——`ChapterMapper.listPlanRows`、`LlmCallLogMapper` 的 findPage/countBy/totalsBy 共 4 处，**MyBatis 启动不报、执行才炸**。已逐条修回，并留了 `var/check_sql_structure.py`（语句块级扫描「AND 开头但块内无 WHERE」+「重复布尔赋值」+「空括号」）。
- **数据清洗**：30 张表软删行、外加 28 本软删书名下未级联标记的关联树（95 章等约 300 行），一次性物理删除约 1000 行。清洗前整库备份 `var/purge-backup-20261003-201839.sql`（183MB）。**另差点犯的错**：章族子表若写 `chapter_id IN (SELECT id FROM chapters)`（未加限制）会把活书的 723 条门禁报告一起删掉，改成显式目标集后才执行——事后核对活数据未动（15 本书、book 1 的 30 章、723 条 gate_reports、1784 条 llm_call_log 全在）。
- **迁移 V37**：给 19 个外键（父为 `novels`/`chapters`/`imported_samples`）加 `ON DELETE CASCADE`——不加的话硬删有内容的书会撞 `chapters_novel_id_fkey`，这正是 AGENTS.md 里记了很久的「必须拍板」那条，本次拍板为级联。`style_packs`/`users` 作父的三个外键刻意不加。
- **刻意保留**：`is_deleted`/`delete_time` 两列 + 8 个条件唯一索引留库当死列/死谓词（同日定调「列留库里当死列，只清代码」）；**3 处 `ON CONFLICT (...) WHERE is_deleted = FALSE` 必须原样保留**（索引谓词还在，去掉会报 no unique or exclusion constraint）。
- **提示词页**：那个恒为 `ref(false)` 的 `showPromptDelete` 开关连同删除列、`deletePrompt` 死代码一并去掉（**后端 `DELETE /api/prompts/{id}` 按用户要求保留**）。
- **证据**：`mvn test` 232/232、前端 `npm run build` 通过、26 个端点实弹 200（另两个非 200 是我自己写错的 URL）、日志零异常；三路删除实弹全通过——删书（书/章/卡/正典/风格包/向量全为 0）、删自定义提示词、删素材卡，均物理消失；全库 `is_deleted=true` 为 0 行。

## 时间三件套上提 `BaseDO`（2026-10-03，用户定调，未提交）

- **要求**：用户「把 do 的能抽出来的公共字段都移植到 basedo 里（如时间等字段）」。抽的口径定为**只抽「每张表都有」的列**：`create_time/update_time/delete_time` 三列在 30 张业务表逐表 `information_schema.columns` 核过俱在、统一 `NOT NULL DEFAULT now()`（`delete_time` 可空）；`novelId`/`status` 这类只在部分表的字段不抽。
- **改动**：`BaseDO` 由「只有 `id`」变为「`id` + 三个 `OffsetDateTime`」；**20 个**各自手写过这三列的实体删掉重复声明（含随之无用的 `import java.time.OffsetDateTime`），**10 个**此前完全没建模的实体（`ChapterDO`/`DigestDO`/`ForeshadowDO`/`LlmModelPriceDO`/`LlmNodeConfigDO`/`MaterialCardDO`/`PresetCorpusDO`/`SceneDO`/`TuningDO`/`UserDO`）就此获得这三列。净 -169/+22 行。
- **和当天另一条定调不冲突**：`isDeleted` 仍不进基类——软删标记不进领域模型这条铁律没动，动的只是它的邻座。
- **知悉并接受的代价**：`update_time` 由 SQL 内 `NOW()` 维护而 MP 更新策略是 NOT_NULL，故「读出来→改字段→updateById」会把旧值写回 SET。全仓仅 2 处 updateById：`ChapterStepDataServiceImpl.finish` 传新建 patch（时间列全 null，不进 SET，安全）；`LlmProviderService.update` 命中——但那是**既有问题**（该实体本来就声明了 `updateTime`，上提前后行为一致），非本次引入。修法（**未做**）：基类 `updateTime` 挂 `@TableField(update = "now()")`。
- **四层证据**：①`grep` 无实体再声明时间列；②`mvn compile` 通过；③`mvn test` **232/232**（注：当天早些时候的状态快照记的是 234，当前实际用例数 232）；④重启实弹：17 个端点全 200、日志零异常；并**打开 mapper DEBUG 看真实 SQL**——MP 为 `ChapterDO` 生成的是 `SELECT id,novel_id,…,reject_reason,create_time,update_time,delete_time FROM chapters WHERE id=?`，**继承字段确实进了列清单**（这一层非看不可：若 MP 没认下继承字段，SQL 依旧合法、依旧 200，只是读出来恒 null，属静默失败）。MP 生成的 `INSERT` 不受影响（null → NOT_NULL 策略跳过 → 走 DB 默认值）；那 10 个新覆盖实体没有任何 entity 级 `insert`/`updateById` 调用点。
- **文档同步**：`docs/code-standards.md` 的 §0 流水 1 / §2 / §3.6 / §4「`BaseDO`」条 / §8.3 追注 / 新增 §8.5；`AGENTS.md`（**该文件在 `.gitignore` 里，属本地文件，改动不会进提交**）铁律与坑 20。按 §0「流程约束」，**规约改动与代码改动要分属两个提交**——目前两者都还在同一个未提交工作区，需用户许可后再拆。

## 命名配对回退：DO 回来了（2026-10-03，用户定调）

- **起因**：用户追问「我 do 去哪了，你擅自规则了？」。查 `git log` 定位到提交 **`8df2bed`（2026-09-21 23:51，142 文件）**——它把 `XxxDO→XxxDTO`、`model/entity→model/dto` 全改了，**并且在同一个提交里重写了 `docs/code-standards.md` 54 行**，把原规则覆盖掉，还补了一节「§8.2 增量」给自己背书。用户记忆里的 DO 是**原规约**（`git show 8df2bed^:docs/code-standards.md` 可证）：`model/entity/XxxDO` 库实体 / `model/dto/` 入参 / `model/vo/` 出参。**规则被覆盖式改写、无独立见证**，这就是用户说的「规则被暗改」。
- **程序性修复**：`docs/code-standards.md` 新增 **§0 规约变更记录**（只追加、不改写历史；记了三条流水 + 8df2bed 的问题）+ **§8.4**（原规则逐条原文 + 三版对照 + 落地进度）。今后改命名/分层：同一提交只许改正文 + 追加流水，**代码迁移单独提交**。
- **代码落地（主体已完成）**：
  - 31 个库实体 `XxxDTO` → **`model/entity/XxxDO`**（含 `BaseDTO`→`BaseDO`），155 个文件 856 处引用已改。
  - `model/vo/` 里 7 个**纯收参**类迁到 `model/dto/` 改 `XxxDTO`（`ApprovalModeDTO`/`NovelCreateDTO`/`NovelQueryDTO`/`PipelineRunDTO`/`PlanAssetQueryDTO`/`PlanModeDTO`/`StyleFingerprintQueryDTO`）。
  - `LoginVO(username,password)` 既收又出 → 拆成 `model/dto/LoginDTO`（入参）+ `model/vo/LoginVO(username, role)`（出参）。顺带修掉一个字段名说谎：原类当响应体用时第二个位置装的是 **role** 却叫 `password`。前端请求体字段名不变，`/api/auth/login` 与 `/me` 出参现在是 `{"username":…,"role":…}`。
- **证据**：`mvn clean test` **234/234**；后端重启后 8 个主接口全 200；登录实弹返回 `{"username":"admin","role":"admin"}`；`grep -rn "\b[A-Za-z]*DTO\b"` 除注释外无实体残留。改名用一次性脚本（`var/rename_entity_to_do.py` / `var/rename_vo_to_dto.py`，Write 落盘不走 heredoc、只做精确标识符替换），改前整树备份在 `var/backup-20261003-132113/`。
- **剩余**：controller **内嵌的 23 个入参 record** 归位 `model/dto/`（这是提取重构、不是改名，另起一批）；原规约 §3.4「POJO 布尔字段不加 `is` 前缀」尚未逐条核（已知 `StylePackDO.isPreset` 违反）；§3.6 的「DO 用 record」确认不执行（与 MP 冲突，已在 §8.4 记因）。
- **本次仍守的边界**：`docs/code-standards.md` 的改动与代码改动**仍在同一个未提交工作区里**（尚未 commit），所以「规约跟着代码走」这个问题这次是靠 §0 流水弥补，不是靠提交拆分——真要彻底解决，需要用户许可后按「规约提交」「代码提交」分开落地。

## 软删标记不进领域模型：`BaseDO` 只留 id、MP 全局逻辑删除撤回（2026-10-03，用户定调）

> **【同日晚些时候已被扩展】**：本条的「`BaseDO` 只留 id」不再成立——用户在「抽公共字段」的要求下把 `create_time/update_time/delete_time` 也放进了基类（见本文最上一条与 `docs/code-standards.md §8.5`）。本条按只追加的口径保留原文；**只对 `isDeleted` 继续成立**（软删标记仍不进基类）。

- **定调**：用户要的是「`isDeleted` 不应该存在」——软删标记不进领域模型。我先前读成「不要每个 DTO 各写一遍」，把它抽进基类、给唯一没有它的 `TuningDTO` 补上，还为此开了 MP 全局逻辑删除（yml `logic-delete-field`）。方向反了，已按本意收口。
- **现状**：`BaseDO`（`@Data abstract`）只放 `@TableId(type = IdType.AUTO) private Long id`——31 个 XxxDO 全部继承（此前 31 份各自手写）。**实体上没有 `isDeleted`**；`application.yaml` 的 `mybatis-plus.global-config.db-config` **整块移除**。软删条件**只**由 DAO 的 SQL 自己带：手写 XML 里 `is_deleted = FALSE` 必须逐条写，这是唯一过滤点。
- **连带退化**：`SampleParseService.updateCard`、`StyleFingerprintService` 原来靠 `isDeleted()` 判软删，字段没了就退化成纯 `== null` 判空（`getById` 不再挡软删行）。`StylePackMapper.findReusablePackId` 早前已收掉「捞已软删包复活」那一支，与本次无关、保持不变。
- **已知缺口（未收口）**：MP 自己生成的 SQL（约 43 个调用点）在没有实体字段时**无法过滤软删行**，`getById` 会取到软删行——`NovelService.requireNovel`、`GenerationQueueService.requireNovelTitle` 这类判空守卫对已软删的 id 失效。库里现存软删行不少：novels 28 / chapters 113 / material_cards 48 / foreshadows 30 / style_packs 28 / world_states 20 / canon_docs 6 …（主列表走 XML 过滤，所以页面上暂时看不出）。要让「软删行不可见」在这个前提下继续成立，只能把软删行**真正删掉**：代码走硬删 + 存量清洗。硬删卡在一个必须拍板的地方——外键是 `NO ACTION`（无级联），`DELETE FROM novels WHERE id=?` 会被 chapters 挡住（实测 `violates foreign key constraint "chapters_novel_id_fkey"`），所以要么给外键加 `ON DELETE CASCADE`（改库结构），要么每条删除路径按依赖序手写子表先行。
- **保留的部分**：`BaseDO` 本身留下（主键一处可改）；`lombok.config` 的 `lombok.equalsAndHashCode.callSuper = call` 留下（Lombok 默认生成的 equals/hashCode 不调父类，主键上移后会被静默排除在相等性比较外）。
- **证据**：`mvn clean test` **234/234**；`GET /api/novels` 仍返回 14 本、探针书 41/42 不出现（列表走 XML 过滤）；后端带新代码重启正常，主要接口冒烟通过。

## 「解析总是从第 18 章开始」的正面答案：链侧口径 + 界面把原因写出来（2026-10-03）

- **根因（前几轮没抓到的那个面）**：用户说「解析」指的是**导入解析链**，而链里 `CHAPTER_OUTLINES` 步骤（`ImportAnalyzeService.chapterOutlines`）**只把号最大的那一卷入队**——设计上就是「只给要写的那一卷出纲」。书 34 的规划卷是第 2 卷，所以无论重跑多少次，任务永远是 **18–27**，第 1–17 章（导入成稿正文）永远不出现。我上一轮改的是**规划页的批量按钮默认范围**，那是另一个入口，没碰到链——这就是「改了还是没好」的原因。
- **改 1（链侧口径）**：目标卷＝号最大的**非导入成稿卷**（`volume_no=1/arc=导入正文` 不算）；**只有导入正文、尚未规划下一卷时本步直接 `SKIPPED`** 并说明原因（此前会入队 1–17 全被守卫跳过，任务消息是「生成 0 · 跳过 17」，看着就像坏的）。抽成可测的 `static Integer plannedVolumeNo(List<ChapterDTO>)`。
- **改 2（界面把原因写在看得见的地方，而不是只在报告里解释）**：规划读模型 `PlanningService.toPlanVO` 补 `hasOutline`/`outlineChars`；规划页
  ①「卷纲」页签在导入成稿卷下面直接写「导入成稿卷：N 章是导入原文，目标/钩子为空是正常的——卷纲/章纲是写之前的规划，成稿章不需要再规划，生成管线从第 X 章接着写」；
  ②「章纲」页签顶部列出**暂无章纲的章号区间**，并标出其中哪些是导入成稿章（`rangeLabel` 压成「第 1–17 章」）。
- **证据**：`mvn clean test` **234/234**（新增 2 条 `plannedVolumeNo` 单测：只有导入卷→null；导入卷+规划卷→取规划卷）。**实弹（探针书 41，2 章导入、只跑 CHAPTER_OUTLINES，零 LLM）**：本步 `SKIPPED`，消息「本书只有导入成稿正文（尚无规划卷）——成稿章不需要章纲，续写卷规划后才出纲」（此前是入队一个全跳过的空任务）。接口核对书 34：卷 1 的 17 章 `hasOutline=false/sceneCount=0`，卷 2 的 10 章 `hasOutline=true/sceneCount=3`。探针已清，读数回到 novels 14 / chapters 204 / packs 23 / scenes 270；另清掉一条 09-30 遗留的孤儿解析任务（探针书 35，QUEUED 未跑，防重启后误烧 LLM）。
- **给用户的一句话**：第 1–17 章不是漏跑，是你的导入原文；**正文生成也永远会从第 18 章开始**（续写＝接着已有正文往后写），这不是 bug。如果你要的是「1–17 也有章纲」，规划页「章纲」页签勾「含已有正文的章」即可（注意：成稿章的章纲是按大纲/前情生成的**事后描述**，不是从该章正文反推的）。

## 章纲批量生成：默认范围＝全书 + 「含已有正文的章」开关（2026-10-01）

- **起因**：用户第三次反馈「解析跑完还是从第 18 章开始，而且全塞在第二卷」。查下来是**观察口径 + 一个真的缺开关**：
  ①库里其实没有「全塞第二卷」——书 34 是 **卷 1「导入正文」= 第 1–17 章（17 章全有正文、0 章纲）+ 卷 2「空船归港」= 第 18–27 章（0 正文、10 章纲）**，两卷 17+10；
  ②用户点的是规划页「章纲」页签的批量按钮，而**该处范围默认是 `1..1`**，第 1 章又是导入的成稿章 → 被守卫跳过，任务消息是 `章纲批量完成：生成 0 · 跳过 2 章`（历史同类实锤：任务 71 `生成 10 · 跳过 22 章`＝17 个成稿章 + 5 个不存在的章号）。「看着永远从 18 开始」就是这两件事叠出来的。
- **改 1（默认范围）**：章纲批量的 from/to 默认改为**全书**（`1..末章`，随所选书加载时算），不再默认只碰第 1 章。
- **改 2（补上缺的开关）**：新增「**含已有正文的章**」勾选（默认关）。勾上后这些章不再被守卫跳过，改为**保全状态出纲**——`OutlineService.generate(..., preserveChapterState=true)` 只写 `outline_yaml` 与场景拆解，**不 markOutlined（状态不退回待生成）、不删门禁报告、不动正文**；对应新 DAO `updateOutlineYaml`。标志随任务 `payload`（jsonb）落库，执行侧 `GenerationQueueService.includeTextChapters(payload)` 解析（坏/缺 payload 一律按老口径跳过）。
  **为什么要「保全状态」这一支**：老路径 `resetForReoutline` 会 `markOutlined`（状态→OUTLINED）并删该章场景/门禁报告，而 `runChapter` 对范围内章**没有**「已有正文则跳过」的判定 → 之后一续跑就会把这一章重写，等于毁掉已写完的正文。保全状态分支把这条毁数据的路堵上，开关才可以安全默认关闭、随时打开。
- **证据**：`mvn test` **232/232**（新增 `OutlineTaskPayloadTest` 2 条：payload 里读开关、缺/坏 payload 按跳过）。**实弹（2 章探针书 40，真 LLM）**：开关关 → `生成 0 · 跳过 2 章`（复现用户现象）；开关开 → `生成 2 章`，库内核对：两章 **状态仍是 FINAL**、正文 89/88 字**未变**、章纲新增 894/1051 字、场景 3/3 物化、门禁报告未被删。探针已清，读数回到 novels 14 / chapters 204 / packs 23。
- **给用户的下一步**：想要《导入书C》第 1–17 章也有章纲，就在规划页「章纲」页签勾上「含已有正文的章」、范围 1..17、点生成（约 1–2 分钟/章、计费）；正文与章状态不会被改。**注意**：这些章的章纲是"事后补的描述"，不会让生成管线去重写它们（正文已成，管线只从第 18 章往后写）。


## 修「规划页 hasText 恒为待生成」（全站性）+ 导入章归卷（2026-10-01）

- **修 1（真 bug，全站性）**：规划页「正文已成 / 规划就绪·待生成」标签恒为后者。根因是 `ChapterMapper.listSummaries` 从 2026-09-15 起就 `NULL AS full_text`（为省流量），而 `PlanningService.toPlanVO` 拿这个字段算 `hasText` → **恒 false**。改用带 `textChars` 的规划读模型（`listPlanRows` 加**可选 novelId 过滤**，规划页按书取；`toPlanVO` 收 `ChapterPlanRow`、`hasText = textChars > 0`，顺带把正文字数一起给出）。
  **实弹**：书 2 的规划页 68 行里 `hasText=true` 从 **0 行 → 68 行**（卷 1 导入正文 28 + 卷 3–6 各 10）；书 34 第 1 卷 17 行全为 `hasText=true`（第 1 章 textChars=1456），第 2 卷 10 行仍为 false（规划章，本就无正文）——两卷对照正好说明标签现在按实况判。
- **修 2（口径，早前记为「未修但已记 ②」）**：**导入正文自成一卷**。`importBook` 落章即写 `volume_no=1 / arc=导入正文`（常量 `NovelService.IMPORT_VOLUME_NO/IMPORT_VOLUME_ARC`）→ 续写卷规划落在**第 2 卷**，规划页不再出现「第 0 卷 · 未分卷」这个伪分组。**存量数据同步补齐**：`UPDATE chapters SET volume_no=1, arc='导入正文' FROM novels … WHERE source_type='IMPORTED' AND volume_no IS NULL` 命中 **45 行**（书 34 的 1–17、书 2 的 1–28）。
  **实弹**：造 2 章探针书走真导入接口 → 落库即 `volume_no=1 / arc=导入正文 / FINAL`，规划接口显示「第 1 卷 · 导入正文（2 章）」且两行 `hasText=true`；探针已清（零残留：活章/卡/向量/包全 0）。
- **顺带的文案**：规划页那个伪分组的表头从「第 0 卷 · 未分卷」改成「未分卷（N 章）」，只有旧数据/非导入书的散章才会命中。
- **证据**：`mvn test` **230/230**（新增 `PlanningServiceVolumesTest` 2 条：`hasText` 来自 `textChars` 而不是 summaries 的 `fullText`、无卷号章归 `volNo=0` 组并标「未分卷」）；前端 `npm run build` 通过；后端重启后接口实弹（书 2 / 书 34 / 新导入探针三条路径）。
- **顺手发现、未处理（报你定）**：书 2「导入书A」有 **卷 2 空缺**（导入正文归到卷 1、已有生成卷是 3–6；卷 1/2 从未存在过，`volume_reviews` 为空），属历史手工编号，不是这次改出来的。要顺号可以把卷 3–6 各减一，或把导入正文拆成卷 1/2 两段——都涉及你的内容判断，我没动。

## 用户重跑实弹复查：卷纲步「顶掉上一卷」已修 + 开关加齐到四步（2026-10-01）

- **用户重跑《导入书C》（书 34）实弹结果**（截图八步全 SUCCESS）：DIGESTS `已处理 17/17 章（覆盖重做）` 326s · 大纲 915 字 · CARDS `新增 20 张、覆盖更新 20 张` 99s · 世界观 1294 字 · 规则 623 字 · EMBEDDINGS `向量已重算 69 条` 31s · **卷纲「第 2 卷已落库：空船归港，10 章（自第 18 章起）」113s** · 章纲 18–27 入队（任务 72，16:43 完成 10 章）。**界面这层因此第一次得到实证**：每行的「覆盖重做/跳过已有」单选、固定口径标注（固定覆盖 / 已有正文的章跳过）、进度行按本次选择回标都对。
- **覆盖路径在真数据上验通**：事实账仍是 **17 行 / 17 章 / 0 软删**（原地更新，没插第二行）；第 1–17 章全 DIGESTED、正文 417–1758 字**未被动过**。
- **同时暴露一个真 bug（已修）**：卷纲步当时是「最大卷号+1 新增一卷」，而起点＝已有正文最末章+1＝18 **恒不变** → 重跑一次就把上一卷的规划行从第 18 章起**整体软删**、换成新卷号的同一段：库内实况＝**第 1 卷「沉船不该再浮」的 10 行全被软删（15:57），18–27 章改挂第 2 卷「空船归港」**，章纲也按新卷纲重出（白烧一轮卷规划 + 10 章章纲）。
- **修法（VOLUME_PLAN 纳入开关，共四步可选）**：按 `planTarget(maxPlannedChapterNo, from, maxVolumeNo, skipExisting)` 决策——**已有规划行覆盖到起点**时：跳过＝完全不动；默认（覆盖）＝**原地重规划那一卷**（同卷号同起点，`adopt` 软删该起点起旧行再插新行）。起点之前真有空档（已有卷只规划到第 10 章、正文已到 17）才新增一卷。这样默认口径不再产生「卷号递增 + 顶掉上一卷」的损耗。
- **证据**：`mvn test` **228/228**（`ImportAnalyzeServiceTest` +4：空档新增第 1 卷 / 覆盖到起点时原地重规划同卷号 / 该情况选跳过 / 有空档仍新增一卷）。实弹只跑了**零 LLM 花费**的「跳过」分支（书 34：`VOLUME_PLAN SKIPPED 第 2 卷已规划到第 27 章（自第 18 章起），本次选了跳过——未动已有卷纲`），跑后核对卷规划行指纹**一字未动**（第 2 卷 / 10 行 / 18–27 / 最后写仍是 16:43:08）、无新增软删、活章 27 / 事实账 17 不变；**「覆盖＝原地重规划」这一支没跑实弹**（要烧一整轮卷规划，且会再次改动用户书的内容），由 4 条单测 + 复用既有 `autoPlan/adopt` 路径覆盖。
- **书 34 的现状（留给你定）**：活跃数据里只有第 2 卷（18–27 章，arc「空船归港」），第 1 卷那 10 行是软删态；`volume_reviews` 为空（第 1 卷没有前置卷，本就没写过复盘）。三种选择：①保持现状（后续把导入章归卷成第 1 卷时，第 2 卷的编号正好接得上）；②把第 2 卷**改号成第 1 卷**（`UPDATE chapters SET volume_no=1 WHERE novel_id=34 AND volume_no=2 AND is_deleted=false`）；③恢复第 1 卷旧行、软删第 2 卷（回到 15:57 的状态，但会连带把 18–27 的章纲也退回旧版）。我没有替你改。
- **仍待你拍板**：①规划页 `hasText` 恒 false 的显示修复（全站性，书 2 的 68 行里 0 行显示「正文已成」）；②导入章归卷（1–17 编成第 1 卷、续写从第 2 卷起）；③章纲「已有正文」的危险开关（默认关闭）。

## 解析链「跳过 / 覆盖」开关 + 两处「已有内容被跳过」的追查（2026-10-01 凌晨，未提交）

- **起因**：用户拿已导入的《导入书C》（书 34）测「勾选清单」，问「为什么只能从第十八章开始生成，而且第一到第十七章莫名其妙是空的」。查下来是**三件事叠在一起**，其中只有一件是 bug：
  1. **只能从 18 章生成＝链的语义**：`VOLUME_PLAN` 走的是「续写」口径——卷号＝现有最大卷+1、起点＝已有正文最末章+1＝18；`CHAPTER_OUTLINES` 只对新规划那一卷入队。导入的 1–17 章从未归卷（`volume_no` 为空），于是有章纲/预算的只有 18–27。这正是早前记的「导入章未归卷，需补一步『把导入章归卷』」，**未做**（口径取舍）。
  2. **「第 1–17 章是空的」＝真 bug（全站性，非这两批引入）**：`ChapterMapper.listSummaries` 从 **2026-09-15** 起就写死 `NULL AS full_text`（为省流量），而规划页「正文已成 / 规划就绪·待生成」标签正是拿这个字段算的 → **hasText 恒为 false**。书 2 规划页 68 行里 `hasText=true` 的有 **0** 行（它有 47 章 DIGESTED）；同一批数据我上批做的「规划资产」页读真实长度，给的是 `hasText=true / textChars=1456`——两页互相打脸。**正文并没丢**：第 1–17 章库里 417–1758 字/章、状态 DIGESTED，`GET /api/chapters/272` 实返 1456 字全文。**此 bug 尚未修**（列在下方待办）。
  3. **章纲对 1–17 章跳过＝生成侧硬闸**：`regenerateOutline` 守卫「已有正文，禁止重出章纲」，因为 `markOutlined` 会把章状态退回 `OUTLINED` 并 `DELETE chapter_scenes / gate_reports`，而 `runChapter` 对范围内章**没有**「已有正文则跳过」的判定 → 下一步续跑就会重写这一章。用户手动点的「批量生成章纲 1–32」（任务 71）实测：`生成 10 · 跳过 22 章`（17 个已有正文 + 5 个不存在的章号）。
- **本批实现（用户定调：「对于已有内容的部分都设置个按钮来控制是否跳过（跳过/覆盖）。默认不跳过」）**：解析链每步带一个「跳过已有 / 覆盖重做」开关，**默认覆盖**，随 `steps` 一起写进任务行（`{"key":…,"skipExisting":…}`，旧裸字符串数组读作不跳过）。真有作用的三步：**DIGESTS**（覆盖＝重算并`原地更新`那一行事实账＋重写世界状态快照；跳过＝不动）、**CARDS**（覆盖＝用新结果更新已有卡但**保留人工 pinned/status**；跳过＝保留人工写过的卡）、**EMBEDDINGS**（覆盖＝清本书旧向量全量重嵌；跳过＝只补缺失——事实账被重算后旧向量对应旧文本，不刷新就会被 RAG 一直召回）。其余五步是固定口径，界面只做说明不给开关（大纲/世界观/规则覆盖、卷纲新增一卷、章纲新卷入队且已有正文的章由守卫跳过）。顺带把步消息改准：DIGESTS 现在区分「已处理 N/M 章（覆盖重做／只补缺失），跳过已有 K 章」，`digest()` 返回值告诉调用方本次到底算没算；CARDS 区分「新增 X 张、覆盖更新 Y 张」。
- **证据**：`mvn test` **224/224**（新增 `ImportAnalyzeServiceTest` 6 条：跳过只对勾选的步生效、默认不跳、旧裸字符串/新对象/坏 JSON 三种任务行读法、无任务返回 null；`DigestServiceTest` +2：覆盖时 `updateContent` 而非 `insert`、无既有行时仍插入；`BookAssetExtractServiceTest` +3：覆盖更新保留人工 pinned/status、跳过一个都不动、新卡两种模式都插入）。**实弹（2 章探针书 38，真 LLM，三轮）**：①覆盖（空库）→「已处理 2/2 章（覆盖重做）· 素材卡写入 13 张 · 向量已重算 15 条」；②**全切跳过** →「已处理 0/2 章（只补缺失），跳过已有 2 章」+ 卡/向量只补了模型这轮新提的那 1 张（跳过只管已有）；③再覆盖 → digest 行 **id 不变、update_time 从 16:15 变 16:19**（原地更新），素材卡**行数不增**（+3 是模型新提的，10 张已有卡原地更新），向量 **旧 id 243–258 全部消失、新 id 259–277 共 19 条**（=2 事实账+17 卡，逐项吻合）。另在**真实旧任务行**（书 34 的裸字符串 steps）上验了向后兼容：接口照旧返回 8 个 plannedSteps、`skipExistingSteps=[]`。探针已清，读数回到 novels 14 / chapters 204 / digests 84 / cards 147 / embeddings 219 / packs 23。
- **待办（本批未做，需你拍板）**：①**修 hasText 恒 false**——给 `listSummaries` 带 `LENGTH(COALESCE(full_text,''))` 而不是 `NULL AS full_text`（仍不带全文，只多一个整数），规划页据此显示真实「正文已成」，并把「第 0 卷 · 未分卷」改成「导入正文（未归卷）」；②**导入章归卷**——把已有连续正文编成第 1 卷、续写从第 2 卷起，规划页就不会再出现「第 0 卷」（是我早前记的「未修但已记 ②」）；③章纲的「已有正文」那一维**没有**做成默认覆盖（用户要的默认不跳过在这里不适用）：开启即退回状态并删该章场景/门禁报告、下一步续跑重写正文，属毁数据动作；要的话我再加一个默认关闭的「危险」开关。
- **运行态备注（踩到的坑）**：`netstat -ano | grep "LISTENING.*:8090"` **永远匹配不上**（端口在 `LISTENING` 之前），要靠它判断「后端在不在」会连续误判——本批两次「重启」其实都因端口被下午 17:03 的旧进程占着而失败（日志写 `Port 8090 was already in use`），接口表现成「新字段没生效」。正确查法：`netstat -ano | grep 8090 | grep LISTENING`（或直接看 PID）。另：`mvn clean` 不会杀掉已加载类的运行中后端，但它会在懒加载新类时崩——重启一律先杀进程再起。

## 修 digest「new_threads 误嵌 state + 漏写收口括号」致整章事实账失败（2026-09-30，未提交）

- **起点**：上一批解析链实弹留下的唯一已知 bug——探针书第 3 章 `digest JSON 解析失败`（fail-open 记「2/3 章」，该章事实账/世界状态/伏笔提议全丢）。
- **取证（拿原始输出，不猜）**：从 `llm_call_log` id=1552 逐字节导出模型原文（已固化为 fixture `src/test/resources/fixtures/digest_new_threads_nested_in_state.txt`）。`finish_reason=stop`、无裸引号、无控制字符——**既不是截断也不是引号问题**：模型把根级 `new_threads` 写进了 `state` 内部，**且末尾少写一个 `}`**（`state` 的收口顶替了根对象的），于是 `LlmJson.read` 的「首个 `{` 到末个 `}`」永远合不上，直接抛。全库回放 1277 条历史 LLM 输出（`closeUnclosed` 能单独救回的行＝该类精确指纹）：**digest 命中 5 条**（真实书 2 的第 40/42/44/52 章）+ sample_chapter 1 条 —— 反复发作的一类，不是孤例。
- **修两层（解析 + 语义）**：①`LlmJson.read` 增加**结构补齐**（`closeUnclosed`：按未闭合栈逆序补 `}`/`]`，作为裸引号修复之后的降级候选）——只补漏写的收口；**字符串未收口（典型截断）一律不救**（半截事实账必须显性失败），括号种类对不上也不猜；②`DigestService` 把误嵌在 `state` 里的 `new_threads` **抬回根层并从快照剔除**（否则该键随世界状态注入后续章节，而提议被静默丢掉），两种情况都 `warn` 显性化。
- **两类畸形的区别（都修了）**：语法合法但误嵌 → 老代码解析得过、提议**静默丢失且零报错**（书 5 第 1 章快照就这样留下非 schema 的 `new_threads` 键）；误嵌且漏收口 → 老代码整章失败。前者更阴。
- **数据修复（历史遗留）**：书 5 第 1 章快照剔除 `new_threads` 键；被吃掉的 2 条提议（草原无名高影 / 冷静玩家身份）**从快照原文取值**、按 `nextCode` 同口径续号为 **F10/F11**（status=proposed，`proposed_in=1`）补回。书 2 那 4 章**没真丢**：失败后重试重跑、同义提议换名重新落库（`proposed_in` 40/42/44/52 均有条目），据此判定无需回填。
- **证据**：`mvn test` **213/213**（新增 `DigestServiceTest` 4 条：真实畸形稿抬升并提议 + 快照不含 new_threads / 根层正常路径不回归 / 根层与 state 双份时根层胜出 / 已有事实账跳过不调 LLM；`LlmJsonTest` +8 条：漏一个闭符、连漏几个、**通篇无 `}` 照旧报错**、真实原文解析后字段数（facts 9 / unresolved 5 / 嵌套 2）、截断不救、配平不动、括号种类不符不动、字符串内括号忽略）。重建重启 8090 后**造 2 章探针书跑真 LLM** 的 DIGESTS 步：**2/2 章**、2 份快照只含 5 个 schema 键、4 条提议、日志 0 次解析失败（这一跑模型没走神，故顺带证明正常路径未回归；畸形路径由上面那批逐字节 fixture 单测锁定）。探针已全清，读数回到 novels 14 / chapters 194 / digests 70 / world_states 60 / foreshadows 271 / packs 23 / tasks 68 / cards 115 / canon 37（与开工一致；`llm_call_log` 记账行按口径保留）。
- **契约固化**：`docs/architecture/pipeline-contracts.md §九`（digest 输出字段归位 + 容错解析边界 + 诊断口径）；AGENTS 坑位 14。
- **边界**：①**不区分「模型漏收口」与「截断落在干净边界」**——都表现为「容器没关但字符串收了」，现在都补；真正区分要靠 provider 的 `finish_reason`，`LlmPort.ChatResult` 目前不带该字段（改它波及所有调用点），留作后续。②嵌套里丢中间收口（括号种类对不上）不猜，照旧报错。③旧账里还有 25 条 digest 解析失败**属另一类**（多为 `<think>` 推理块里的大括号让「首个 `{`」起错位置）——**未逐条核**，且走 `ask` 重试的节点本来就会把失败轮留档，故不等于数据丢失（digest 不走 `ask`，详见本文档「已知口径」），本批未动。

## 导入书籍「解析链」：LLM 生成物可勾选补齐（2026-09-30，未提交）

- **需求**：导入书籍后，把素材库能通过 LLM 生成的东西 + 大纲/卷纲/章纲一起接上解析，**用户自己勾选要跑哪些（默认全勾）**。
- **素材库 LLM 生成物盘点（决定勾选清单）**：事实账+世界状态+伏笔提议（`LlmNode.DIGEST`，一章一次）、素材卡（新增 `LlmNode.BOOK_CARDS`）、世界观文档（`SAMPLE_WORLD`）、文风规则（`STYLE_RULES`）、向量索引（`EMBEDDING`）；再加大纲（`SAMPLE_OUTLINE`）、卷纲（`VOLUME_PLAN`+前置 `VOLUME_REVIEW`）、章纲（`OUTLINE`）= **8 步**。指纹是机械统计（零 LLM）故不入链，仍在导入弹窗单独勾。
- **实现**：`import_analyze_tasks`（**V36**：一本活书一行活跃任务，重复提交＝重置；`steps`/`done_steps` 两个 JSONB）+ 全局单线程 runner（与样本深度解析同构）+ 逐步 fail-open 且**每步跑完立即落库**（关页面/重启都不丢进度，前端 4 秒轮询）。端点：导入时 `analyzeSteps` 一并入队（提交在事务外，否则 runner 读不到未提交章行）、单独提交/查进度 `POST/GET /api/novels/{id}/import-analyze`。**API 语义：不传/空＝不解析**（纯接口建书不会默默烧 LLM），界面默认全勾。
- **提示词口径**：只新增 `book_cards` 一个节点；大纲/世界观**复用** `SAMPLE_OUTLINE`/`SAMPLE_WORLD` 提示词（提示词只在 PromptCatalog 一份，双造必然漂移）；素材卡走「章节结构+事实账」一次抽卡（不逐章 `SAMPLE_CHAPTER`，省 17 倍调用）。
- **实弹（3 章探针书跑满 8 步，真 LLM）**：DIGESTS 已补 2/3 章（第 3 章 digest JSON 解析失败，见下）· OUTLINE 403 字 · CARDS 21 张 · WORLD 1487 字 · RULES 571 字 · EMBEDDINGS 23 条（=2 事实账+21 卡，逐项吻合）· VOLUME_PLAN「第 1 卷已落库：牌底叁号，12 章」（255s，含 AI 审校 2 轮重写）· CHAPTER_OUTLINES 第 4–15 章入队（队列已在产出，12 章里 7 章落章纲）。落库核对：canon 3 篇（大纲/世界观/卷1简报）、chapters 15（3 导入+12 规划）、foreshadows 13、world_states 2、scenes 21。**探针已全清**（12 张表逐表软删，收工读数 novels 14 / chapters 194 / packs 23 / cards 115 / embeddings 170 / canon 37 / tasks 68，与开工一致；llm_call_log 按口径保留作计费证据）。
- **实弹抓到的三个真 bug（全部已修）**：①`INSERT ... RETURNING id` 用 `<update>` 标签 → MyBatis 返回影响行数（拿回 `taskId=-1`），整轮解析的进度全写丢（进程照跑但 UI 看不到）——改 `<select resultType="long">`；②jsonb 列用 MP `save()` 绑 varchar → `column "steps" is of type jsonb but expression is of type character varying`——改 XML 显式 `::jsonb`；③**RULES 步对导入书是死步**：`extractRulesForNovel` 只认「关联样本/预设的品类语料」，而导入书两者都没有 → 每次必失败。改为**回落到本书正文当语料**（同一个 `STYLE_RULES` 提示词），重跑该步实测产出 571 字规则（第三人称限知/句长节奏/物象密度…）。这三条都写进 AGENTS 坑位 13。
- **未修但已记**：①第 3 章 digest JSON 解析失败（模型输出合法但 `llmJson` 解析不过，同一章重试 2 次仍失败）——~~digest 路径既有脆弱性，非本批引入~~**（2026-09-30 已修，见本文件顶部「修 digest「new_threads 误嵌 state…」章节：真因是根级 `new_threads` 误嵌 `state` + 末尾少写一个 `}`）**；②导入章未分卷，首次卷规划把**新卷编为第 1 卷**（接在正文之后，如第 4–15 章），若要「导入章＝第 1 卷、续写从第 2 卷起」需要加一步「把导入章归卷」，未做（口径取舍，等确认）。
- **前端**：导入弹窗新增「导入后解析」勾选组（8 项、默认全勾、每项带耗时/口径提示，可全选/全不选）；导入完成 → 先弹提指纹（要人确认）→ 关窗即弹**解析进度面板**（逐步 ✅/⏭/❌ + 耗时 + 汇总，关掉也继续跑）；书籍管理行内「补事实账」按钮改为「**解析**」（面板里可只勾 DIGESTS 等任意子集重跑）。前端步骤清单单源于 `web/src/importAnalyze.js`（与后端枚举逐项对齐）。
- **测试**：`mvn test` **201/201**（新增 `ImportAnalyzeStepTest` 6 条：全序/勾选重排/未知键忽略/空勾选不解析/标签齐全/汇总文案；`BookAssetExtractServiceTest` 6 条：素材卡 JSON 映射与去重、未知 kind 归 misc、大纲 Markdown 渲染跳空段、围栏剥离）。前端 `npm run build` 通过；后端已重启在 8090，工作区与运行态一致。

## 新增「规划资产」页：大纲/卷纲/章纲三层统一列表 + 条件查询（2026-09-30，未提交）

- **起因**：用户问「导入书籍的大纲、章纲、卷纲在哪里进入 LLM 解析并落库」并要求「这块也要有专门的页面展示，查询条件也要补齐」。答案固化进 `docs/architecture/pipeline-contracts.md §七`（三层的落库位置/写入方/触发入口一张表），页面即本批新增的 `/plans`。
- **三层落库位置（一次说清）**：①**大纲**＝`canon_docs`(kind=misc, name=大纲) 一本书一行，写入 `PlanningService.saveStory`，入口 `POST /api/novels/{id}/outline-draft`（异步，节点 `derive_outline`）或规划页人工写；②**卷纲**＝`chapters` 规划行（`volume_no`/`arc`/`budget_min|max` 逐章一行，一卷多行）+ 卷复盘 `volume_reviews.report`，写入 `VolumePlanService.adopt`（先软删 fromNo 起旧行→插新行→伏笔建账），入口 `POST /planning/volume/auto-plan[-async]`（manual 模式出草稿再 `/volume/adopt`）；③**章纲**＝`chapters.outline_yaml`，生成管线逐章产出（节点 `LlmNode.OUTLINE`），批量 `POST /planning/outline/batch`、单章 `POST /chapters/{no}/outline/regenerate`。**导入书籍（IMPORTED）天生三样都没有**——只有正文（`status=FINAL`），要接着写必须先补这三样，这正是新页面要暴露的缺口。
- **读侧实现**：`GET /api/plan-assets`（`PlanAssetController` → `PlanAssetService`，只读聚合）+ `web/src/views/PlanAssetView.vue`（路由 `/plans`，侧栏「规划资产」）。三层共用一条宽读模型 + `level` 判别，前端 tab 切换、按层渲染不同列。空条件 = 该层全量，默认「书 + 卷号 + 章号」通读序。
- **筛选条件（16 个）**：level、novelId、sourceType、keyword、volumeNo（**0＝只看未分卷**的导入章）、fromChapter/toChapter（卷层按章号区间**相交**）、status（**仅章纲层生效**）、hasOutline、hasText、skeleton（**仅大纲层生效**，找「克隆了样本骨架还没改写」的书）、minChars/maxChars（大纲层按大纲字数、其余按正文字数）、from/to、sort（ORDER_ASC 默认/TIME_*/TEXT_DESC/OUTLINE_DESC/TITLE_ASC）。语义由 `PlanAssetService.matches/comparator` 纯函数锁定（11 条单测），「与层级无关的条件在不适用的层上不生效」是明确口径而不是疏漏。
- **缺口清单是主线**：缺大纲的活书、缺章纲的卷/章**也出行**并标 `hasOutline=false`（行底色标淡黄），页面顶部读数直接给「有 N / 缺 M」；卷层还带「未复盘」与「未分卷组」。导入书因此一眼可见：`导入书C` 17 章全是 `FINAL`+有正文、无章纲、未分卷、无大纲。
- **性能取舍**：规划行一次跨书取齐（新 `ChapterMapper.listPlanRows`），正文**只带长度不带全文**（184 章 × 约 4 千字，带全文近 1MB）；章纲 YAML 约 1.2KB/章故原样带出供展开阅读；软删书的残留章行不进列表（按活书过滤，与指纹页同口径）。
- **证据**：`mvn test` **189/189**（新增 `PlanAssetServiceTest` 11 条：层级作用域、关键字命中书名/目标/大纲正文、缺口三态、`volumeNo=0`、卷区间相交、字数与时间区间、默认读序、时间排序 nullsLast、坏日期给 A0001）；前端 `npm run build` 通过；**接口实弹与 SQL 逐条对账**——章纲层 184 行 / 无章纲 114 / 未分卷 45 / FINAL 35 与 psql 完全一致，卷层书 2（未分卷 28 章、卷 3–6 各 10 章）与书 34（未分卷 17 章）逐卷吻合，大纲层 14 本活书里 12 本有纲、2 本缺（正是两本导入书 导入书B / 导入书C）、2 本仍是样本骨架；关键字「导入书C」命中 23 章（书 18/19 各 3 + 导入书C 17）；取书 9 大纲正文片段检索 → 只命中书 9 且原文带出。**字段契约核对**：页面读的 31 个字段接口全部返回（脚本比对，无缺失）。
- **边界**：①页面只读，改大纲/卷纲/章纲仍回「规划」页（或书籍管理续导向导）；②`level` 缺省 CHAPTER、未知值落 CHAPTER；③未分卷组没有卷号也没有 `arc`，卷层按「未分卷」标签显示；④没有做「从规划资产跳回规划页对应行」的联动（未做，避免两个页面互相扩接口）。

## 同族排查：软删残留 / 改名撞活行 / 异常原文外泄（2026-09-30，未提交）

- **起因**：用户要求「找系统里是否还存在类似问题」。把全库 **18 个条件唯一索引**（`... WHERE is_deleted = FALSE`）逐个与「软删点 × 插入点 × 更新点」配对核。
- **先纠一个语义（决定整轮排查方向）**：条件唯一索引本来就不收软删行，所以「先软删旧行、再插同样 key」是**安全**的——真正的病根只有两类：①**该删没删、活着占 key 的残留行**（style_packs 那次删书留下的孤儿包）②**编辑改名撞在用的活行**。第二轮探针一开始按错误前提去找「软删后能不能插」，结论会被带偏，这点已写进 AGENTS 坑位。
- **安全清单（逐条有证据）**：world_states / volume_reviews / embeddings 写入 / foreshadows 三条 insert 全部是 `ON CONFLICT DO UPDATE` 或先查后改；llm_model_price 与 tuning 运行时**只有按 id/tkey 的 UPDATE**（无插入无删除）；sample_parse_tasks 复用活行；chapters 走「卷规划先软删再插」（软删行不进索引，所以重排安全）；canon_docs / prompt_templates / llm_node_config / llm_providers 的 create 都先查活行、delete 都是软删。
- **修 1（确定有 bug，UI 可达）**：`MaterialCardService.update` 缺同名活卡校验——`create` 一直查、`update` 原先没查，把卡名改成同类型下另一张活卡的名字必撞 `uq_material_cards_novel_kind_name`。补 `existsOther`（自己除外），撞了给 A0006 人话「同类型下已存在同名卡」。**同类写法对照**：`LlmProviderService.update` 一直有这个校验，只有素材卡漏了。
- **修 2（同族的「删了不传播」）**：RAG 召回 `EmbeddingMapper.search` 只滤 `embeddings.is_deleted`，不看源行死活——**软删的素材卡/事实账，其向量仍在被召回、注入生成上下文**（删了卡系统照它写，界面上完全看不出原因）。真库脚印：novel 2 与 novel 18 各有一条已删事实账的向量仍在召回（66→65、3→2）。改成召回时 EXISTS 校验源行存活（digest 连章一起看），`findMissingDigests` 也补 `c.is_deleted=FALSE`。选**查询期过滤**而不是删向量，是为了把库里已经躺着的陈旧行一并挡掉。
- **修 3（幂等兜底）**：`SampleParseTaskDataServiceImpl.resetForRun` 的「先查没有 → 再插入」并发窗口（解析按钮双击）会撞 `uq_sample_parse_task_alive`；改成撞了就重查并复用那一行。捕 `DataIntegrityViolationException` 父类 + 以「能否重查到该行」判定真假并发，**不让修复取决于 Spring 把 23505 翻成哪个子类**；非并发约束冲突照旧上抛（双跑本身早有 `runParse` 的 QUEUED→RUNNING CAS 兜住）。
- **修 4（异常出口）**：`GlobalExceptionHandler` 补 `DataAccessException` 分支（连不上库 / SQL 错）→ D0001 人话。此前这类会落进 `handleOther`，把 `org.postgresql...` 驱动栈与 JDBC 主机端口抛到界面。
- **实弹证据**：①素材卡三例——撞活卡给 A0006 人话、改成空名放行、改成别的 kind 占用的名字**不误杀**（唯一键含 kind）；②往不存在的书建卡（外键冲突）→ A0006 人话，无 SQL；③**docker pause 造「库无响应」**→ 30s 后 `{"code":"D0001","message":"数据库暂时不可用或执行失败…"}`，无 JDBC 原文，unpause 后立刻 200 恢复；④召回谓词在真库上按 novel 逐条比对旧/新计数，差额正好是那两条陈旧向量。**环境备注**：排查中途 Docker Desktop 引擎挂过一次（`docker ps` 报 unable to start），库短时不可用，重启 Docker Desktop 自愈；另注意 `pg-vector` 的 restart 策略是 `always`——`docker stop` 8 秒就自己回来，要造断库窗口得用 `docker pause`（这次踩到，第一次 stop 测试打了个空）。
- **单测 178/178**（新增 `MaterialCardServiceTest` 3 条、`SampleParseTaskDataServiceImplTest` 3 条、`GlobalExceptionHandlerTest` +1）。探针数据全部清理，收工读数 novels 13 / packs 23 / chapters 177 / cards 115 / embeddings 170 与开工一致。
- **明确留账（未改）**：①伏笔 code 是「读 MAX 再插」的非原子写法，`VolumePlanService.createForeshadow` 那条 insert 没有 try/catch（digest 那条有）——并发写同一 novel 时理论上会撞 `uq_foreshadows_novel_code`，单跑不可达，要改需产品判断；②活孤儿包不做后台清扫，只在复用点回收；③`deleteNovel` 不级联章节（既有口径，便于整本恢复）；④陈旧向量只在召回期被过滤，不主动清库（过滤已是 fail-safe，清理属可选卫生）。

## 修「删书后同名重导必炸风格包唯一键」（2026-09-30，未提交）

- **起因**：用户删掉《导入书C》后按建议重导，界面红色横幅滚出 `duplicate key value violates unique constraint "uq_style_packs_name_alive" 详细: Key (name)=(导入书C·风格) already exists` + 整段 `INSERT INTO style_packs …` 与 mapper 文件路径。**根因是删书只软删 novels 行**：书 25 已删，但它那份风格包 36「导入书C·风格」还活着，把活名唯一约束的位置占着（`uq_style_packs_name_alive ON style_packs(name) WHERE is_deleted=false`），同名重导的 `insertPack` 必撞。上一批收尾时我已把「孤儿包」列为已知口径并说"界面上看不到"，**没料到它会在同一天以硬报错的方式咬人**——留了口子就要它还债。
- **两条修**：①**删书级联回收专属包**（`StylePackMapper.softDeleteOrphanOfNovel`）——非预设、且已无其他活书引用才删（共享包/预设永不碰），从此不再长新孤儿；②**开书与导入统一走 `NovelService.acquireStylePack`**：同名可复用包（已软删的、或活着但无活书引用的孤儿包）**原地改写复活**（保留原 id 与干净名字，不产生 `·2`）→ 活名空缺则新建 → 活名真被在用的包/预设占着才退让成「书名·风格·2」。
- **顺手补的出口卫生**：`GlobalExceptionHandler` 新增 `DataIntegrityViolationException` 分支——原先落进 `handleOther` 兜底，把整段 SQL、表结构、`target/classes/mapper/...` 路径当 `message` 抛给前端横幅（既看不懂也泄露库结构）。现在完整堆栈只进服务端日志，界面只给一句「同名记录已存在或数据违反约束——换个名字再试」。
- **实弹（四条路径全走真接口 + psql 对账）**：①新建 `ZZ重导探针-勿留` → 包 39 新建；②`DELETE /api/novels/27` → 包 39 被级联软删（旧代码此处留孤儿）；③同名重导 → **包 39 原地复活、名字仍是「ZZ重导探针-勿留·风格」**（没有 `·2`）、新书 28 落 2 章；④用 psql 造一个活孤儿包（复刻用户包 36 的处境）→ 同名导入**复用该行**（id 40，全库同名行数仍为 1）；⑤psql 造一个占名的**预设** → 同名导入退让为「ZZ占位探针·风格·2」，预设 41 与 `is_preset=TRUE` 均未被覆盖。用户真文件 `导入书C.docx` 在当前构建上重导（探针书名）→ **17 章、状态 FINAL、每章 417–1758 字**，与上一批结论一致。
- **单测**：`mvn test` **171/171**（新增 `NovelServiceStylePackTest` 6 条：名空则新建 / 孤儿包复用不新建 / 活包占名退让 `·2` / 50 次仍占给人话 A0006 / 删书级联触发 / 删书被拒时不动包；新增 `GlobalExceptionHandlerTest` 2 条：约束冲突不出 SQL 且码为 A0006、其他异常仍是 B0001 原形）。写这批单测时踩了 **Mockito 对 `Long` 返回型默认给 0 而不是 null** 的坑——`findIdByTitle`/`findReusablePackId` 不显式桩就分别被当成「已有同名书」和「有可复用包」，两条都不是产品逻辑而是 mock 默认值（测试里已注明）。
- **未动用户的账**：书 25《导入书C》与其包 36 保持原样（没替他们重导——弹窗里预设/补账勾选我看不到）。**他们现在再点一次「导入书籍」就会成功**：包 36 会被原地复用，名字仍是「导入书C·风格」。另：`deleteNovel` **不级联章节**（既有口径），所以书 25 还留着 1 行活章（那份 1 章版），重导后成为不可见残留；同类残留还有书 7（6 章）、书 24（3 章）。探针已全部清理：novels 13 / packs 23 / chapters 177（packs 比上次基线 25 少 2，是顺手收掉了我自己早前遗留的两个孤儿包 25/27）。
- **边界**：①包被级联软删后，psql 恢复书**建议连包一起恢复**（`style_packs` 无 `@TableLogic`，引用照旧可读，所以不恢复也不会立刻报错，但界面/素材库看不到该包）；②活孤儿包不做后台清扫，只在复用点回收；③本书风格包名冲突只在「另一个活包/预设占着同一名字」时才改编号，同书重导不再产生 `·2`。

## 切章认中文数字分节 + 修「空指纹让门禁炸」（2026-09-30，未提交）

- **起因**：用户导入《导入书C.docx》后发现系统「不认章节」——整篇落成 1 章。查真数据：该文件用 **「一、登船 / 二、商人 / …十七、尾声：导入书C之后」** 这 17 行分节（每行 4–11 字，标题不是正文），而切章只认「第N章」。
- **切章规则扩到三种行首标题**：①「第N章」阿拉伯数字（既有口径不动）②「第X章」中文数字（第一章 / 第十一章）③「X、标题」中文数字 + 顿号/点/冒号（一、登船 / 十二、漂流）。新增 `NovelService.parseHeading` + `chineseToInt`（一→1、十→10、十一→11、二十一→21、一百→100），②③ 带**整行 ≤30 字长度闸**——没有它，「一、他想起那件事的时候正在下雨……」这类正文行会被当章标题把书切碎（单测专门锁这条）。
- **实弹**：用用户那份 导入书C.docx 重导 → **17 章、标题全对**（登船/商人/船长与海/…/尾声：导入书C之后），每章 417–1758 字，状态 FINAL，重排=false（中文数字解析出的章号本就 1..17 连续）。
- **修掉一个会让书"生成即炸"的洞**：新的「预设可选」导入逻辑在未选预设时建**空风格包**（fingerprint=NULL），而 `GateService.fingerprint()` 原实现对 null 直接 `readValue(null)` → 抛 `IllegalStateException("fingerprint 不可解析")`。也就是说：**未选预设导入的书，只要不做提指纹就采纳，一生成就 B0001 + 章 FAILED**；而代码注释与界面提示都写着「无配置时回退 tuning/代码默认值，不会跑挂」——**描述与实际相反**。已改成 fail-open（无指纹→warn + 跳过指纹类指标，其余硬规则照跑）。
- **fail-open 的第一版仍然是错的（单测抓出来）**：返回裸 `Map.of()` 会在下游 `fingerprintChecks` 取 `fingerprint.get("baseline")` 时 NPE——**fail-open 变成换个地方炸**。正确形状是 `Map.of("baseline", Map.of())`。这正是「失败检查=没检查」的又一例：不写那条单测，这个改动会以"已修好"的姿态上线。
- **证据**：`mvn test` **163/163**（新增 `NovelServiceTest` 4 条：中文数字章标题、分节标题、长度闸不误判正文、按分节切书；新增 `GateServiceNoFingerprintTest` 4 条：null 指纹不抛、空白指纹不抛、无基线时写作提示返回 null、坏 JSON 仍抛错——最后一条守住「真损坏要报错、缺配置才 fail-open」的边界）。
- **未动用户的账**：用户的 《导入书C》 书（id=25）仍是他们导入时那份 **1 章**版本（0 digest / 0 世界状态 / 0 LLM 调用 / 未采纳指纹），**我没有替他们改**——建议删掉重导一次即可得到 17 章。另发现他们的测试书 `无预设导入验证-0929`(24) 已自行删除，但其风格包 35 成了孤儿行（`deleteNovel` 不删风格包，属已知口径；文风指纹页只列活书的包，所以界面上看不到）。
- **边界**：长度闸是启发式（30 字）——中文数字开头的长正文行不会被误判，但真正的长标题（>30 字）会被漏判成正文；「卷」级标题（第一卷）不识别为章边界。

## docx 支持 + 修掉「拖不认识的文件灌乱码」（2026-09-30，未提交）

- **起因**：用户想把 《导入书C.docx》 拖进「导入书籍」，结果正文框被灌成 `PK…` 乱码（截图里「已载入 6.3 万字」是二进制当文本读的长度）。两个问题一次修：①系统不认 docx；②**拖拽区没有类型守卫**——浏览器只在系统文件选择框上按 `accept` 过滤，拖进来的文件不判类型，不认识的二进制被 `readAsText` 当 utf-8 读，静默塞进正文框。这是我当天早些时候写拖拽区时留下的洞。
- **新增 `DocxExtractor`**（`common/util/`，只用 JDK 的 zip + StAX，零新依赖）：docx 本质是 zip，取 `word/document.xml`，按 `w:p` 段落 / `w:t` 文本运行还原正文；`w:br`/`w:cr` 换行、`w:tab` 制表；**修订删除（w:del）与域代码（w:instrText）跳过**（否则改动痕迹与页码域会混进正文）；空段落压缩（Word 里空段落极多，不整理会灌进上千空行）；关外部实体解析防 XXE。
- **分派按文件头，不按扩展名**：新增 `DocumentTextExtractor` 统一入口（原先 `MobiExtractor.extractFromBase64` 的位置）——PK→docx、`D0CF11E0`→旧版 .doc（**明确拒绝**并提示「Word 另存为 .docx/.txt」；解析二进制 doc 要引 POI，不值得）、其余→MOBI。拖拽来的扩展名不可信，服务端按字节判才稳。
- **前端**：拖拽区自判类型（`.doc` 直接给另存为的指引、未知扩展名报「不支持的文件类型：xxx」而**不再静默塞乱码**）；accept 与文案更新为 `txt / docx / mobi / azw`。入参字段 `mobiBase64` 改名 **`fileBase64`**（它现在装的是文档而不只是 mobi 电子书），三个导入入口与两个 VO 一并改。
- **证据**：`mvn test` **155/155**（新增 `DocumentTextExtractorTest` 8 条：段落还原、w:br 换行、w:del/w:instrText 跳过、行内制表保留 vs 行首缩进去掉、空段压缩、缺 document.xml 拒绝、OLE2 旧版 doc 拒绝并给人话、非 zip 非 OLE2 落 MOBI 解析器、base64 的 data URL 前缀与体积守卫）。**真实文件实弹**：用用户的 `导入书C.docx`（0.06MB，1301 段）走 `POST /api/novels/import` → 成功导入 1 章；落库正文 **13446 汉字 / 1300 换行 / 18101 字符**，与独立用 Python（zipfile+正则）分析同一文件的数字**逐项完全一致**（13446 / 1300 / 13100+换行），且正文里 `PK` 与 `<w:` 标签均为 0——两套独立实现互证，乱码路径已封死。探针数据已全部软删（书 13 本 / 风格包 23 / 章节 173 回到基线）。
- **已知取舍**：①旧版 `.doc` 不支持（要另存为）；②该 docx 用「一、二、三、」分节而非「第N章」，所以整篇落成第 1 章（导入响应 notes 已明说，用户若想让「一、」也当章边界，是切章正则的一行改动，等确认）；③docx 里的**图片/表格内容不提取**（只取文字，表格里的文字也会被拼进段落流）。

## 按本书正文提指纹：A（导入时）+ B（任意已有书）（2026-09-30，未提交）

- **背景**：用户问「书籍管理导入的书能不能拿去生成文风指纹」——答案是不能：书籍风格包的指纹是从品类预设**逐字克隆**来的（13 本书里 10 本的风格包指纹与某预设逐字相同），而提取入口（`GenrePresetService.analyze/extractDraft`）只读 `preset_corpus`，不碰 `chapters.full_text`。能力其实早就有，只是在命令行侧（`tools/style_baseline.py` 产出 `style-metrics.json` → `ImportRunner` 导入）。
- **新增两段**：`POST /api/novels/{id}/style/extract-fingerprint`（草稿，机械指标/零 LLM/不落库）+ `POST .../apply-fingerprint`（采纳）。**A** = 导入书籍弹窗勾选「导入后按本书正文试提指纹」→ 导入成功后自动弹草稿；**B** = 书籍管理行内「提指纹」按钮，任意已有书（含 AI 生成的书、CLI 导原稿的书）都能重校准。两者共用同一份草稿弹窗与同一个服务方法。
- **口径单一源（三处复用，没造第二份）**：数学直取同包的 `GenrePresetService.buildBaseline/budgetBand`（样本单元＝一章）；指标中文名走 `GateService.metricLabel`；指标行新增顶层 `FingerprintMetricVO.parse`，指纹页与草稿弹窗共用——**指纹页原来内联的指标表也抽成 `FingerprintMetricTable.vue`**（消掉第二份表头定义），前端不再有第二份指标中文名映射。
- **三条硬约束**：①正文 <2000 汉字直接拒绝，章数 <10 低置信、<3 再给「样本过少」强提示（与品类语料同一阈值口径）；②指纹 JSON 由前端原样回传（所见即所得），后端只校验「含非空 baseline 的 JSON 对象」+ 章长带区间（300-20000、容差 0-0.5、下限≤上限）；③**采纳必经草稿确认**，且「勾了同步章长带却不给数值」报错而非静默跳过（静默会让用户以为带已同步——这条是单测逼出来的）。
- **实弹踩到的交汇（重要，故意不改门禁）**：`dialogue_end_punct_ratio` 在场景级/章级都有硬下限 0.5（既有反 AI 腔规则）。拿真实书试提时发现两种撞法：①「导入书B」自身基线 **0.09**（该书的风格就是对话句末不加标点）；②全书对白句末普遍无标点 → 该指标被 `buildBaseline` 的全零剔除、基线里根本没这一项，而 `GateService.lowerBound` 取不到基线时**回退硬下限 0.5**，判定照旧。两种情况草稿 notes 都会明说「采纳指纹不改这条既有规则，续写章节会被要求给对白句末加标点」。是否给这类书放开硬下限是产品决策，本次只如实提示。
- **顺手修**：`GateService.METRIC_LABELS` 补 `tic_laizhe_per1k`/`tic_shunbian_per1k`——现有风格包里实际存在这三项口头禅指标，缺的两个此前在指纹页以键名裸奔（`tic_shunbian_per1k`）。
- **证据**：`mvn test` **147/147**（新增 `BookFingerprintServiceTest` 9 条：2 章样本出低置信草稿且指标带中文名、无正文/正文过少/书不存在三类拒绝、采纳的 JSON 形状与带宽区间四类校验、采纳写指纹+合并章长带且保留原有键、不勾同步则不写带、无风格包拒绝、`applyBudgetBand` 纯函数合并）；`npm run build` 过（新组件独立成 chunk）。**实弹**：B 路径对「导入书B」出草稿（5 章 12910 字、9 指标、低置信、章长带 1700-3350）✓；正文 <2000 字的书被正确拒绝（`A0001 本书正文只有 660 字，太少`）✓；A 路径全链路——导入 8 章 2616 字探针书 → 草稿（4 指标、章长带 300-350 且对白句末 1.0 不再触发硬下限提示，条件判定正确）→ 采纳后**风格包指纹 9→4 项、gate_config 合并出 budget_min/max/tolerance 且原键 banned_phrases/no_straight_quote 保留** ✓，文风指纹页该行读数同步变为「指标 4 · 章长带 300-350」✓。
- **探针与清理**：探针书（21/22）及其风格包、章节全部软删并核对（书 13 本、风格包 23、章节 173），列表无残留。**注意**：核对时发现书数比基线少 1——是「星际厨神的深夜食堂」（id=13）在 01:15 被**经应用删除接口**软删（`auto_message='书籍已删除，无人续跑已关闭'` 是 `NovelService.deleteNovel` 才写的字样；该书写删前无任何章节，属空测试书），**不是本次清理所为**（我的语句 `WHERE id IN (21,22)`，命中行数与预期逐条对齐）。如需恢复：`UPDATE novels SET is_deleted=false, delete_time=NULL WHERE id=13`（其风格包 23 未删）。
- **边界**：指纹不可手改（只有「按正文重提」与「应用到本书」两条覆盖路径）；提指纹只读正文、不动正文与章状态；无回滚快照——回退靠「素材库 → 品类预设 → 应用到本书」。样本单元固定为「一章」，不支持按场景/块统计（想调粒度得改 `draft` 的取样方式）。

## 书籍管理：入库类型 + 查询条件 + 导入书籍（2026-09-30，未提交）

- **需求**：书籍管理加「入库类型」区分手动导入/系统衍生/系统纯原创；补齐查询条件；开放「导入书籍」按钮（复用既有 txt/mobi 解析能力）。
- **先说清楚「之前那个导入」是什么**：系统里唯一能读 txt/mobi 的是**导入小说/样本**那条链路（`POST /api/preset/analyze` + `MobiExtractor`，落 `imported_samples` 台账与 `preset_corpus` 语料，**不建书**），素材库与开书向导各有一个入口；本轮把它提取电子书正文的能力（`MobiExtractor.extractFromBase64`，含 data URL 前缀剥离 + 体积守卫）抽成共用方法，样本导入与书籍导入共用一条路径，消掉原先内联的一份复制。
- **V35 迁移**：`novels.source_type`（IMPORTED/DERIVED/ORIGINAL + CHECK 约束）。幂等回填：`derive_config->>'sourceSampleId'` 非空 → DERIVED，其余 → ORIGINAL；再一次性修正 2026-09-10 由 CLI `ImportRunner` 原稿导入产出的 2 本（导入书B/导入书A——`derive_config` 为空会被上一步误判为原创），同时**让 ImportRunner 直接写 IMPORTED**，以后不再靠回填。实测分布：DERIVED 10 / IMPORTED 2 / ORIGINAL 2。
- **后端**：`NovelSourceType` 枚举（`normalize` 大小写不敏感、未知值兜底 ORIGINAL；`ofSample(sampleId)` 是开书路径唯一判据）；`NovelVO` 加 sourceType；`list(NovelQueryVO)` 支持关键字/入库类型/状态/审批模式/无人续跑/章数区间/创建时间/排序，筛选与排序是纯函数（`matches`/`comparator`，单测锁定）；`POST /api/novels/import` 切章落库；`POST /api/novels/{id}/digest-backfill` 补事实账。
- **导入语义（三处刻意的设计）**：①**文风预设必填**——`GateService.fingerprint` 拿不到指纹会直接抛异常，导入一本没有风格包的书等于埋一个必炸的书，故与开书同规则强制选预设；②**章状态用 `FINAL`**（导入正文终态，新增进 `ChapterStatus` 枚举；与 ImportRunner 既有口径一致），不进生成状态机，ChaptersView 显示「导入正文」、预算列显示 —（导入章没有预算，原先会显示 0-0 像坏了）；③**补事实账必须与导入分成两次调用**——落库是短事务，事实账是逐章 LLM（§6 禁止事务内 LLM），且用户该先看到「导入了 N 章」再决定要不要花这个钱；前端导入弹窗默认勾选补最近 3 章，逐章失败只记 note 不断导入。
- **默认排序的边界（避免误伤）**：`GET /api/novels` 同时给全站 7 处「作品下拉」供数，**无 `sort` 参数时保持历史 id 升序**；书籍管理页自己在筛选栏里显式传 `TIME_DESC`（新导入的书落在首行并高亮）。
- **前端**：BooksView 加筛选卡片 + 命中读数（三类计数）+ 入库类型标签列 + 导入书籍弹窗 + 行内「补事实账」；导入后若当前条件没命中新书，明确提示「点重置可看到」。
- **证据**：`mvn test` **138/138**（新增 `NovelServiceTest` 16 条：筛选六类条件、日期含当日与非法的 A0001、默认排序仍是 id 序、三种显式排序、切章（标题行不入正文/前言并入首章/无标题单章）、章号重排与不改写、入库类型归一）；`npm run build` 过且 vite dev 转译正常。**实弹**（后端重启 + V35 应用，now v35）：负例缺预设报 A0001 ✓；正例导入 245 字 2 章 → `novelId=20`、chapterCount=2、renumbered=false、notes 空 ✓；落库核对 `source_type=IMPORTED`、两章 `FINAL`、正文长度 114/115、标题「夜航/灯塔」（标题行的「第N章」未进正文）✓；11 组查询条件逐条核对（三类入库类型、关键字、章数、草稿、人工审批=0 生（库里确实全是 auto）、无人续跑、时间区间、组合条件、非法日期报 A0001）✓；**补事实账实弹成功**（recent=1 → requested=1/digested=1/notes 空，真实 LLM 调用）。
- **探针数据已还原**：书/章/风格包/digest/world_state/伏笔提议全部软删（书 14 本、章 173、风格包 23、digest 67 回到基线，列表无探针残留）；**仅保留该次 LLM 调用在 `llm_call_log` 的记账行**——调用台账是计费审计，删它等于伪造账目。
- **边界**：导入只做「切章 + 落正文 + 可选事实账」，不生成卷纲/大纲，续写前需在「规划」页自行做卷规划；入库类型不可人工改（它是来源事实，不是可编辑属性）；「人工审批」筛选当前恒 0 生（历史书全部为 auto）——筛选本身已验证，不是 bug。
- **导入改为拖拽区（同日补，用户要求「导入部分都做成能直接拖进去的样式」）**：新增共用组件 `web/src/components/TextFileDropZone.vue`（拖进来或点击选择，读成 `{name,text,mobiBase64}` 后 `emit('loaded')`），三处导入点全部换成它：书籍管理「导入书籍」、素材库/指纹页共用的导入小说弹窗、开书向导「导入我的小说分析」。**顺带消掉三份重复的读文件逻辑**（原先三处各有一份 FileReader 分支 + 文件名兜底，共约 60 行）。两个实现细节：①拖拽不走 `accept`（浏览器只在选择框上过滤），组件按扩展名自判类型——电子书走 base64 交后端 `MobiExtractor`，其余按 utf-8 文本读；②拖入/离开高亮用**计数**而不是布尔（鼠标划过区域内子元素也会触发 dragleave，布尔写法高亮会闪），非文件拖入（拖来一段文字）给提示而不是静默失败。
- **验证与限制**：`npm run build` 过且 `TextFileDropZone` 独立成 chunk；vite dev 对组件与三处引用点的转译均正常；grep 确认全站只剩组件内部一个 `type="file"`、三个旧 handler（`onSampleFile`/`onImportFile`/`onFile`）已无残留；emit 载荷字段（name/text/mobiBase64）与三处 handler 的解构逐字对齐。**未做真实拖拽手势验证**——本机只有可见 iab 浏览器（用户全局规则禁用），拖拽事件链（dragenter/dragover/drop）与点击选择共用同一个 `take(file)`，选择文件那条路是既有逻辑，拖拽那条路仅由构建 + 转译 + 静态契约核对覆盖，实际手感请用户自己拖一次确认。


## 文风指纹独立页：三来源统一台账 + 条件查询（2026-09-30，未提交）

- **需求**：指纹提取结果此前散在三处（素材库→风格包的「指纹基线（只读）」、品类预设的「提取草稿」、导入小说的「文风分析快照」），用户要单独开一个页面汇总成列表并带筛选查询条件。
- **后端**：新增只读聚合 `GET /api/style-fingerprints`（`StyleFingerprintController` → `StyleFingerprintService`，**零新 SQL**，全走既有 DataService：`ImportedSampleDataService.listAlive`、`StylePackDataService.listPresets|getById|findGateConfigById`、`NovelDataService.listAlive|findDeriveConfig`、`PresetCorpusDataService.genreSummaries`）。三来源统一为一行 `StyleFingerprintVO`（source=SAMPLE/PRESET/BOOK），`metrics` 在后端就把 baseline 展开并附中文名。
- **口径单一源**：`GateService.METRIC_LABELS` 由 private 提升为 `public static metricLabel(key)`（类内两处 `getOrDefault` 一并改走它），指纹页与写作提示共用同一份指标名映射——前端不重建映射（防双源漂移）。
- **查询条件**（`StyleFingerprintQueryVO`，字段全空=不筛；>3 条件按规约收成对象）：来源、关键字（名称/品类/说明/标签）、品类、置信度（LOW/HIGH）、指标数下限、语料字数区间、提取时间区间（yyyy-MM-dd，含当日）、排序（TIME_DESC 默认 / TIME_ASC / METRICS_DESC / CHARS_DESC / NAME_ASC）。
- **读模型口径**（三处易混，代码注释已写明）：①书籍行的品类经 `derive_config.sourceSampleId` 回链源样本，**语料规模留空**（书籍没有「提取语料」这一读数；带字数条件时书籍不算命中，避免假命中）；②预设行的品类与语料规模经样本台账 `preset_id` 回链（style_packs 不存品类），章长带取自身 gate_config；③历史 backfill 样本 `analysis` 为空 → 回退用其采纳预设的指纹（仍有指标可看），章长带仍为空。
- **前端**：新增 `web/src/views/FingerprintView.vue`（路由 `/fingerprints`，侧栏「文风指纹」）：筛选卡片 + 命中读数（样本/预设/书籍分布、覆盖品类）+ 列表（来源/名称/说明/品类/语料规模/指标数/章长带/置信度/关联/提取时间）+ 展开行（提取 notes、与现有预设相似度、样本标签）+「指标明细」弹窗（基线/容差/显式下限/上限 + 原始指纹 JSON 可复制，并注明门禁判定口径）。
- **顺手修**：`StylePackDTO` 补 `createTime/updateTime`（库列本就有，DTO 漏建模，与「软删三件套一并建模」共识对齐）。
- **页面内导入入口（同日补，用户要求「直接在指纹列表页加入口」）**：把素材库「导入新小说」弹窗抽成共用组件 `web/src/components/SampleImportDialog.vue`（`v-model` 开合 + `imported` 事件回传分析结果；组件只负责读文件与调 `POST /api/preset/analyze`），指纹页顶部加「导入文章提取指纹」按钮，素材库原弹窗改为引用同一组件（删掉其内联的表单/读文件/提交约 45 行 + 3 个状态）。成功后重拉列表高亮新行；**若当前筛选条件没命中新行，明确提示「点重置可看到」**（否则表现为「导入没生效」）。
- **证据（指纹页本体）**：`mvn test` **122/122**（新增 `StyleFingerprintServiceTest` 9 条纯函数用例：空条件不筛、来源/品类精确、关键字命中名称/品类/标签、字数条件排除无语料行、日期含当日两端、非法日期报 A0001、降序 nullsLast、未知排序回落默认）；`npm run build` 过 + vite dev（5173，用户实际入口）转译新页与路由正常；后端重启后实弹 `GET /api/style-fingerprints` 全量 25 条（样本 5 / 预设 6 / 书籍 14），另 12 组条件逐条核对（单条件、组合条件、非法日期、三种排序）。**首版降序排序把 null 排到了最前**（`nullsLast(...).reversed()` 连带翻转空值位置，书籍行反而顶在 CHARS_DESC 首位）→ 改 `nullsLast(reverseOrder())` 并复验。
- **证据（导入链路实弹，含数据还原）**：以 611 字探针正文走 `POST /api/preset/analyze`（与弹窗同一接口）→ `sampleId=10`、1 块、530 字、2 项指标、低置信 true、品类唯一化命名、建议 new；随后 `GET /api/style-fingerprints` 由 25 → **26 行且新行按默认排序落在首行**（即页面上被高亮的那条），`keyword=探针` 命中 1 条。**探针数据已还原**：样本行软删 + 该品类语料行软删 → 样本 5 行、指纹 25 行、`/api/preset/genres` 无探针品类残留（三处核对全绿）。
- **边界**：列表本体只读，改指纹/门禁仍回素材库与品类预设页（页内导入是唯一写入动作，且走既有 `analyze` 管线，不新增落库口径）；导入的深度解析（LLM 拆剧情/资产卡）仍只在素材库行内触发；未做分页（当前 25 行，量级上来再加）；**未做可见浏览器走查**（用户全局规则禁用 iab 可见面板），以「接口实弹 + 生产构建 + 模板字段契约核对（模板读到的字段与接口返回逐一对齐）+ 遗留引用 grep」替代视觉走查。

## LLM 接入按用途分流：会话走 DeepSeek / 向量化固定 MiniMax（2026-09-29，代码已落地，DeepSeek 接入待 key）

- **动机**：会话与向量化共用一行接入，把会话切到别家会连带把 MiniMax 私有协议（`/embeddings`+`texts`+`base_resp`）的请求发到不支持它的服务上。
- **改动**：迁移 V34 给 `llm_providers` 加 `role`（chat/embedding，默认 chat，幂等把原唯一启用行回填为 embedding+model=embo-01）；`LlmRole` 枚举 + `LlmProviderResolver.resolve(role)` 严格按用途取行（取不到回退 yaml，**不借用别用途的行**）；单活从"全平台一行"改为"每用途一行"（`disableAllOthersInRole`）；接入管理 CRUD/VO/前端加用途字段；**连通测试按用途走各自协议**（会话 `/chat/completions`、向量化 `/embeddings` 且真解析 `base_resp.status_code`）；`MiniMaxEmbeddingClient` 固定取 embedding 行且模型读行内值。
- **会话客户端兼容修正**（换 DeepSeek 的坑）：`extractContent` 不再在 content 为空时把 `reasoning_content` 当正文（DeepSeek 下会把纯思考写进稿件，改为 warn+空）；缓存命中 token 兼容 `prompt_tokens_details.cached_tokens` 与 `prompt_cache_hit_tokens` 两种命名（否则命中价恒 0）。
- **顺手修 bug**：`LlmModelPriceMapper.xml` 的 update 表名写成复数 `llm_model_prices`（实际 `llm_model_price`）——**素材库改价目一直必报 relation 不存在**；连带 DTO `@TableName` 与三处注释修正，现在改价目可用。
- **实弹证据（不依赖 key 的那半，已全绿）**：V34 应用成功（now at v34）；两行并存时**启用会话行不会停掉向量化行**；会话行连通测试走 chat 协议 ok（3.7s）、向量化行走 embeddings 协议 ok（1.5s）；真实会话调用（`POST /api/chapters/209/review`）log `model=MiniMax-M3` 非 embo-01，说明没被 id 更小的向量化行抢走；向量化在会话行并存时仍出 `embo-01` 向量（书 9/10 回填 9+5 条）。mvn test 113/113、前端 build 过、后端 PID 12156（V34 后）。
- **DeepSeek 已接入并实弹（2026-09-29 完成）**：官方规格表脚注明确**模型名用 `deepseek-flash`**（`deepseek-v4-flash` 是已下线旧名，仍可调通但返回 `model=deepseek-flash`、按 Flash 计费）——库里按官方名落库；BASE URL `https://api.deepseek.com`（不带 `/v1`，两种都实测可通）。探针结论：`stream_options.include_usage` 接受、思考走 `delta.reasoning_content`、**会发 `data:[DONE]`**（客户端已防御性接收）、终帧 usage 带 `prompt_tokens_details.cached_*`、坏 key=HTTP 401 干净错误体（确定性拒绝，走"4xx 不重试"分支）、**思考模式默认开启且可用 `{"thinking":{"type":"disabled"}}` 关掉**（`chat_template_kwargs.thinking=false` 实测无效）、官方输出上限 384K 故长文节点无需显式 max_tokens。
- **实测差异（供后续观察）**：同一章 ai_review，DeepSeek 55.8s / MiniMax-M3 92.4s；**同一章 MiniMax 判 minor 而 DeepSeek 判 blocker**（指出"罗经脉动错半拍"的连续性硬伤）——换模型会改变评审松紧，门禁摩擦与成本可能随之变化。
- **价目**：按官方表加行 `deepseek-flash`（缓存命中输入 0.02、未命中 1、输出 4 元/百万，高峰翻倍）；应用侧实算一次调用成本 0.0473 元（与手算一致）。注意官方真实高峰是**工作日 9-12 与 14-18**，当前表结构单窗口按 14-18 近似（偏差约 ±3%，已写进行备注）——要精确需加第二高峰窗口（未做）。
- **演练行**：临时会话行 `id=2 MiniMax 会话（分流演练）`（复用 id=1 密文）现已被同用途互斥自动停用（建 DeepSeek 行时自动停的，实弹证明该规则生效），留作回滚资产：停用 id=3 即回退 yaml（MiniMax）或直接启用 id=2。
- **key 已按约删除**：只以 AES-GCM 密文存在于库内，明文不落盘不入 git。
- **已知相邻缺口（本次未动，仅记录）**：`llm_providers.connect_timeout_ms` **行级连接超时实际不生效**（共享 HttpClient 只用 yaml 的 connectTimeout 建一次；行级 read_timeout 生效）；价目高峰窗口只有单段、且不含"仅工作日"维度。

## 代码层 P0/P1 批修复与文风复沓闭环实弹（2026-09-29，未提交）

- **P0-1 向量回填静默失效**：EmbeddingMapper.findMissingDigests 只投影 2 列，而 MissingRow 是五字段 record——缺列不是少个值而是整条查询炸（回填端点必失败）。补齐 sourceId/chapterNo/content/kind/name。实弹：`/api/novels/6/embeddings/backfill` 65→69（68 卡+1 digest；64 条上限分两次收敛），书 18/19 各 +3 digest 向量，新日志「惰性向量索引失败」0 条。
- **P0-2 复沓清单落库即弃**：读者评审第 6 问产出的 repeat 清单此前只进 gate_reports，重写轮拿不到（提示词却承诺「直接喂给下一稿」）。ReviewService.readerFix 逐条消费为「复沓（同一句/近似句反复）：…　只保留一处，其余删掉」。配套提示词三处（scene_draft 红线 / reader_review 第 6 问 repeat / reader_fix 硬条款）走 PromptCatalog 同步进库（custom=FALSE，DB 已 v3/v5/v4）。
- **评审后机械复检**：ChapterPipelineService.reviewStep 在评审改写落库后补跑章级门禁，只留痕（事件 + gate_reports）不自动再改——评审改写绕过门禁造成的指纹漂移（第 3 章实测行均 17.97→21.50）从此可见。
- **fail-open 补日志**：GateService 5 处 catch（失败摘要/门禁配置/参数解析/评审标准/指纹目标）+ ContextPackerService.worldState 加 warn。两类此前都**没有 logger**（@Slf4j 缺失，加了 log 直接编译不过）——顺带证伪「邻路已有 warn」的先验。
- 同批：MiniMaxClient 流式传输失败重试 1 次 + 4xx 确定性错误不重试短路；generation_tasks.last_message 截 256 字（僵尸任务 #58 根因）；.gitignore 封 novel 原稿（docx/txt/mobi/azw3 不入 git）；tools/style_baseline.py 的 UnboundLocalError 修掉。
- **证据**：mvn test 106/106（0 失败 0 错误）；后端重启提示词同步 81 行；P0-2 实弹——向 ch3 注入整段逐字复沓 + 上章「沉船全灭」式断裂结尾，reader r1 判 hook=fail+continuity=fail → BLOCKER → reader_fix 请求实带 5 条复沓清单行 → 重写后注入块 ×2→×1、3668→3330 字、r2 pass；复检事件 `{"passed": true, "recheck": true}` 落库。
- 制 BLOCKER 的经验（可复用）：结构性四问全过时 fat 超标会被 downgradeFatOnly 降级为 pass；最可控的强制手段是 hook——reader_review 提示词明文「环境/氛围/抒情式开场=不会继续读」。
- 测试污染已全部还原（ch2/ch3 正文与状态、ch2 摘要、3 张备份表删除；digest 75/82 与 world_states 71 未被波及）。

## 诊断修复六项全落地 + 深层真凶翻转（2026-09-28，未提交，任务 57 重出验证通过）

- 六项修复全实弹通过，另挖出**第二层真凶**：场景门禁 `GateService:138` 把 dialogue_end_punct_ratio 当**上限 ≤0.35** 执法（导入书B校准遗留）——修复后的合规新稿（0.889）反被旧门禁打回、章级下限 ≥0.5 与场景上限数学互斥。已翻转为**下限**语义：场景级 lowerBound（abs_min 优先 > max(0.5, 基线*(1-tol)) > 0.5 硬下限），章级 fingerprintChecks 同口径；新增 GateService.lowerBound 助手。
- **附赠基建修复**：MiniMaxClient.streamPost `queue.take()` 无限阻塞（JDK HttpClient timeout 只覆盖响应头，流中途挂死=任务永卡，57 实卡一次）→ poll 空闲看门狗（max(60s, readTimeout) 无帧→retryable 失败）。
- 六项状态：①画像拔除（代码删 TemplateDef+注入点，DB 停用 id2/47）✓；②量化口径单一源（仅 fingerprintGuidance）✓；③世界观占位 ✓（实弹验证提示词五要素全对：无画像块/有句末指令/有提炼规则/有指纹目标/世界观占位）；④规则提炼（STYLE_RULES 节点+extractRulesForNovel+POST /api/novels/{id}/style/extract-rules+素材库按钮）✓ 实弹提炼 15 条；⑤对话标点盲区（场景+章级双下限）✓ 新稿 0.889 过闸；⑥重出：56 停止、ch1-7 重置（保章纲清草稿/软删 digests+world_states）、57 重出 1-10。
- **新 ch1 验证**：三场景过闸（顿号 19.0 vs 18.11 轻微超限由重写轮收敛）、拼章 3342 字进章级门禁；引号内句读全合规（0.889），「……」他道 式说话动词直连为提炼规则的译本排版惯例（统一、非破损）；文风显著带上源主角式宿命感。
- 教训入账：门禁上限/下限语义混在同一指标名里无标注——新指标进指纹必须显式声明方向。

## 诊断修复执行完毕：六项全落地（2026-09-28，未提交，实弹验证中）

- **修1+2 画像拔除/单一源**：PromptCatalog 删 style_redlines 兜底 TemplateDef + ContextPacker.packScene 不再叠加（system=本书规则正文）；DB 停用 prompt_templates id=2/47。量化口径唯一来源=GateService.fingerprintGuidance（本书基线）。
- **修3 世界观 null**：world() 空文档时用 PromptCatalog world_placeholder 占位（原样拼接渲染字面 "null"）。
- **修5 对话标点盲区**：fingerprintChecks 对 dialogue_end_punct_ratio 内置硬下限 0.5（基线缺失同样生效；基线有值取 max(0.5, v*(1-tol))，可 abs_min 覆盖）；derivedDirectives 无条件下发"对白句末必须带标点"写作指令。
- **修4 规则提炼**：LlmNode.STYLE_RULES + PromptCatalog 模板 + GenrePresetService.extractRulesForNovel（语料=derive_config.sourceSampleId→品类，兜底风格包关联样本；16k 字节选）+ POST /api/novels/{id}/style/extract-rules + 素材库风格包按钮。实弹：test5 提炼出 15 条规则（含对话标点规范），已写回风格包进 system。
- **附赠基建修复**：MiniMaxClient.streamPost 队列 take() 无限阻塞（JDK HttpClient timeout 只覆盖响应头，流体挂死=任务永久卡死，57 号任务实卡一次）→ poll 空闲看门狗（max(60s, readTimeout) 无帧即 retryable 失败，零帧客户端重试/有帧走自愈梯子）。
- **修6 重出**：56 停止（其续跑复用 PASSED 污染场景+新门禁必败死循环，停止正确）；ch1-7 重置（status=OUTLINED、full_text 清、场景草稿清 gate PENDING、digests/world_states 软删，章纲保留）；任务 57 重出 1-10。提示词五项实证通过（无画像块/有句末指令/有提炼规则/有指纹目标/世界观占位）。
- 验证中：等 57 产出新 ch1 正文后测对话标点比例与可读性。

## 【重大诊断】test5 正文不合格根因（2026-09-28，已定位待修）

- **用户报告**：源主角衍生-test5 生成正文"完全不合格"。通读 ch1-5+源语料+风格包+实际提示词后定位：
- **主因：错配的全局兜底"量化风格画像"**（prompt_templates id=47，scene_draft/style_redlines，"无画像预设时叠加"）——内容是**导入书B校准画像**（对话密 19 行/千字、一行一拍 15-22 字、**对话行句末 85% 不加标点**、"时薪18""31块"、守则条文序号），被注入所有无画像预设的书。模型服从度 94%（实测 5 章 169 处 」后 160 处无标点直连叙述）——破对话标点的直接来源。
- **次因①同提示词自相矛盾**：画像（一行一拍 15-22 字/省略号 4-6/顿号 ≤1）vs 源主角指纹执行口径（每行均长 68-179 目标 100/禁省略号/顿号目标 6.5）同 prompt 打架——模型左右横跳，门禁反复打回（也是生成慢/烧钱的一大来源）。
- **次因②rules_md 全空**：6 个预设+全部书籍风格包 rules_md 长度=0——"写作规则"层从未产出（规则提炼未落库，fail-open 静默）。
- **次因③世界观渲染字面 null**：克隆世界无内容时提示词仍输出"【世界观（必须遵守）】null"。
- **盲区**：指纹 8 指标全为叙述标点密度，无对话标点维度——破标点门禁全过、还进对白范例自我强化的通道敞开（ch4 实测未见范例段，范例提取宁缺毋滥生效中）。
- **修复方向**（待用户拍板）：①参数化/删除 id=47 兜底画像（无画像书不再注入别家口径）；②画像与指纹口径合并单一来源；③规则提炼补产或显式禁用；④世界观 null 时不渲染该段；⑤指纹补对话标点指标；⑥修后重出 ch1-5。

## 会话行可展开看思考与输出（2026-09-28，未提交，纯前端）

- **用户反馈**：过程日志行（AI 章纲/场景生成/审校…）只能看 tokens/耗时，不能展开看 LLM 思考和最终生成结果。
- **改动**（WorkbenchView，零后端）：trace 打底的调用行带 logId（CallItem 本就有 id），行尾加「思考与结果」展开——懒加载 GET /api/llm-logs/{id}（LlmLogDetailVO 本就分区返回 reasoningText/content/promptMessages），内嵌块分"思考（N 字）"与"最终输出（N 字）"两段各自限高滚动。修复过程中一次 Edit 误删函数签名的自伤（构建+grep 双验恢复）。
- **边界**：仅 trace 打底的行可展开（SSE 实时事件不携带调用 id）；当前章的实时行重开会话/切章重打底后即可展开。实测：ai_review 详情 68,860 字思考+完整 JSON 输出正常返回。

## 生成透明化补课：节奏读数+门禁原因透出（2026-09-27，未提交，纯前端）

- **用户反馈**：任务跑得久且"只能看到粗略阶段"——①会话历史打底（trace 回放）的门禁行只报"未过"不带原因（实时 SSE 路径本就带 reason，打底渲染丢了）；②全任务无时间宏观读数（还要多久/为什么慢）。
- **改动**（WorkbenchView）：①seedSession 门禁行挂 gateFailBrief——机械门禁逐项"指标=实测（限，基线）"、读者/审校报告给判定+注水率+问题清单，点"原因"展开；②taskPace 读数（已运行 X 分 · 均 Y 分/章 · 预计还需 ~Z）：队列进度格下方 + 会话头部，nowTick 随 3s 轮询刷新，首章未完成时降级提示。
- **实测口径（test5 任务 #56）**：每章 8-11 次 LLM 调用、单章 LLM 纯耗时 8-18 分钟（ch1 1068s/203k tok，ch2 592s 其中章级修订单轮 319s）——慢在质量管线（场景门禁重写/章级修订/读者评审/AI 审校），不是卡死；10 章约 2-3 小时为正常量级。

## 配置收口：本书生成参数单一入口（2026-09-27，未提交）

- **用户诉求**：业务侧只配一次（向导），后期有唯一入口改参数；且评审标准数字不知实际效果。
- **根因修复**：updateDeriveConfig 此前不重算门禁——后期改掺水量三阈值不跟随（与开书换算脱节）。现 water 变更时自动 applyWaterGates 重算写入本书 gate_config（其余门禁键不动），与开书同一换算单源。
- **前端**：①工作台删除常驻「评审标准（本书）」卡片（五项数字裸奔无语义是困惑源头）；②「衍生参数」弹窗升级「本书生成参数」=唯一参数入口：掺水量滑杆实时预览换算三阈值（新端点 GET /api/novels/{id}/water-gates?water=N，复用 DeriveSupport.waterGates 防双源漂移；拖动联动高级区）、审批模式/规划模式收进弹窗、质量口径五项收进"高级"折叠并逐项标注大白话效果；保存一次写全（derive-config + approval-mode + plan-mode + gate-config 合并）；③顶部只留运行决策（审批模式/连跑范围/优先级/启动）；④向导完成页注明"参数已落库，工作台「本书生成参数」随时可改"。
- **验证**：编译+106/106 单测+build 过；实弹——探针书 water 50→70 后 gate_config 三阈值 0.33/0.50/0.60→0.40/0.56/0.56 自动重算 ✓，water-gates 预览端点 ✓，探针书已删。**注意**：本次重启打断了用户刚提交的任务 #56（CHAPTERS 1-10），队列断点续跑自动恢复 RUNNING——重启恢复路径实弹又验一遍。

## 章纲 tab 全景化：跨章全量展示+三维度筛选（2026-09-27，未提交）

- **需求**：章纲（场景拆解）页默认只看单章，用户要全量展示+按章节/内容/素材筛选。
- **后端**：SceneMapper.listByNovel（JOIN chapters 按章号+场景号排序）→ SceneDataService → PlanningService.allScenes（挂 ChapterSceneVO：chapterNo/chapterTitle/sceneNo/goal/present/mustReveal/mustNot/wordsBudget）→ GET /api/novels/{id}/planning/scenes。
- **前端**（PlanningView 章纲 tab 重构）：默认拉全量跨章平铺表格（新增「所属章纲」列，点击即筛该章；tab 标签带总场景数）；筛选行=章纲下拉（带各章场景数）/素材下拉（present 并集按出现次数排序，filterable）/内容搜索（目标+必揭示+禁出现+出场 不区分大小写 contains）+清空+「X/Y 场景 · 涉及 N 章」实时统计；表格 max-height 滚动；筛选为 computed 前端即时计算零请求。原「查看章节」spinner 移除（被章纲下拉取代），批量生成行/进度横幅保留。
- **验证**：编译+106/106 单测+前端 build 过；重启实弹——GET /planning/scenes 返回 9 场景跨 3 章（满月刃语/南下雾岭/古寺听雪），行结构含章题与完整拆解。

## 章纲批量生成任务化：OUTLINE 队列类型（2026-09-27，未提交）

- **用户报告**：章纲生成在工作台看不到进度只看到结果——原「生成本章章纲」是同步 HTTP（请求线程里跑 1-2 分钟 LLM），不经过生成队列/SSE；且不支持批量区间。
- **改动**：①TaskKind 增 `OUTLINE`（generation_tasks.kind 无 CHECK 约束，零迁移）；②GenerationQueueService.submitOutline + runOutlineTask worker 分支——逐章复用 regenerateOutline，守卫失败（已有正文/无规划行）跳过不中断、LLM 异常记失败续走、章间可硬停（taskThreads 中断在飞调用），终态消息带生成/跳过/失败计数；进度走 updateProgress，事件走 StageLog.OUTLINE（start/done/reuse/failed 带 taskId+chapterNo，工作台会话可按任务打界回放）；③端点 POST /api/novels/{id}/planning/outline/batch {from,to}；④规划页章纲 tab 改为「批量生成：第 X 至 Y 章（入队）」+ 横幅进度条 + 轮询复用（pollPlanTask 扩展为 PLAN/OUTLINE 双盯，完成后自动刷场景表）；⑤工作台：队列行范围列「章纲 X-Y」、当前阶段「章纲生成中」、会话标题/回放分支、outline 实时事件带章号。
- **验证**：编译+106/106 单测过；重启后端实弹——#53 批量 2-3 章 RUNNING 进度可见（0/2→1/3 章生成中）→ DONE「生成 2 章」，两章 OUTLINED 各 3 场景落库，pipeline_events outline start/done ×2；#54 跳过路径（11-12 无规划行）DONE「生成 0 · 跳过 2 章」零 LLM 调用。**坑**：重启命令必须带 JAVA_HOME=jdk-21，否则默认 JVM17 报 UnsupportedClassVersionError 65>61。

## 章纲生成前端入口（2026-09-27，未提交）

- **背景**：用户卷纲规划完找不到章纲生成界面——章纲本是启动生成后管线自动的首步（且 `ChapterPipelineService:633` 章纲已存在即复用跳过），前端只有卷纲行一个"重出章纲"按钮（措辞像只能重出）。
- **改动**（纯前端 PlanningView.vue）：①章纲（场景拆解）tab 加「生成本章章纲」按钮——按输入章号调 outline/regenerate，成功后拆解表直接刷进本 tab，附语义提示（约 1-2 分钟/章、覆盖重建、有正文拒绝、提前出后面章节缺前情链、量产建议交管线）；②卷纲行「重出章纲」更名「生成章纲」（首次/重出两用）。
- **验证**：npm run build 过；实弹——POST /api/novels/16/planning/chapters/1/outline/regenerate 返回 3 场景拆解并落库，章节状态 NEW→OUTLINED（启动生成时将复用）。

## 向导页草稿劫持修复：废弃/忽略/重开三入口（2026-09-27，未提交）

- **用户报告**：开新书向导"卡住"——进 /wizard 检测到草稿书且大纲任务 DONE 就静默 `resumeDraft()` 自动跳大纲步，横幅随即消失，用户被锁死在旧草稿上（书名禁用、无废弃、无重开入口），横幅只提示"去书籍管理页删除"（那边删除还要手打全书名）。
- **修复**（纯前端 WizardView.vue，后端 DELETE /api/novels/{id} 对 draft 本就放行，守卫只看 generation_tasks）：①横幅加「废弃草稿」（确认后软删，删的是当前接续书则重置向导）/「不管它，直接开新书」两按钮，多本草稿显示剩余数；②接续后大纲步常驻提示带：「废弃重开」+「留着草稿，开新书」（重置但草稿放回横幅可再续接）；③initWizard 拆出 resetWizardState/detectDraft(autoResume) 复用；④大纲轮询加 seq 过期守卫——废弃/重开后旧任务迟到结果不再回填新表单。
- **验证**：npm run build 过；实弹探针——POST 造 draft 书（id 15）→ 列表可检出 → DELETE 成功 → 列表消失。用户卡住的两本草稿（13 星际厨神/14 骨架门禁验证）未被触碰，刷新向导页即可自助处置。

## 门禁摩擦治理：指纹目标进提示词+合格稿范例+量尺校准（2026-09-27，未提交）

- **用户报告**：task #51 章级机械门禁反复打回。根因两层：①指纹量化指标（行均长度/标点密度/口头禅）只存在于门禁，**场景生成提示词里一个数字都没有**——写手闭卷作文，全靠修订轮试错；②更隐蔽的反向毒源：开篇/对白"风格范例（逐字原文严格模仿）"取的是本书**没过门禁的碎句稿**，每章都在强化坏文风。
- **修复**：①GateService.fingerprintGuidance()——按门禁同一套判定数学（abs_max 优先/稀疏指标上限/其余 ±tolerance/abs_min 下界）把指纹翻译成写作口径，packScene 注入新提示词段 scene_draft/fingerprint_targets（PromptCatalog 可编辑）；②from fingerprint 推导执行口径（基线≥40 → 长句指令+严禁碎句排版；省略号上限<0.5 → 禁用；破折号克制；tic_* 能删则删）；③范例只从过章级机械门禁的合格稿取（GateReportDataService.passedChapterIds 新查询+开篇/对白范例过滤，无合格稿宁缺毋滥）。
- **校准**：line_avg_len 对该书不可达（4 轮修订震荡在 34-38 vs 下界 68——译本统计值 vs 对白为主题材的自然区间）——pack 22 指纹加 abs_min=30（杀 13-17 病态碎句、放过 35-60 可达带、目标仍 100 立足长句导向）；字数 2700-2850 窄带同根源未动（已能收敛）。**校准只动本书风格包，预设不动；嫌目标 100 仍高可把 value 降 60。**
- **验证**：106/106 测试（+3 指纹口径）；实弹——scene_draft 请求含指纹目标与执行口径；第 3 章 abs_min 校准后 2 分钟内 PASS，任务推进第 4 章。
- **设计备忘**：衍生链"题材变文风不变"= 三层配合——提示词给指标+范例给合格样本+量尺适配模型自然区间；缺一层就靠修订轮硬磨（每章多烧 4×5min LLM 调用）。

## 卷规划流式透明化（2026-09-27，未提交，实弹 1129 增量全通）

- **背景**：用户反馈卷规划"等很久一点东西都没有"——查明**没卡**（3 分钟跑完生成 142s+审校 46s+第 1 轮 BLOCKER），是卷规划 LLM 调用非流式、会话视图只在阶段边界有事件的固有沉默。另勘误：#49 并非永久死锁，是卡满 600s 读超时才以 I/O error 收场（llm_call_log #1007 为证），"有界但迟钝"。
- **修复**：①VolumePlanService 新增 PlanThinkRelay（章级 ReviewThinkRelay 卷级同款，只转 think 增量）——askPlan/reviewPlan 走 llmJson.ask 四参流式，规划/审校的思考流实时进会话（emitLive CHUNK 不落库）；②审校发 START/VERDICT 持久事件（round/verdict/issues），回放也能看到轮次判定；③前端 WorkbenchView 新增 pstream 转录块（思考折叠/流式中标签）+chunk 增量追加+重写轮自动开新块；④接入读超时 600s→300s（页签可调）。
- **实弹**（book12 auto-plan）：420s 窗口 1129 个 think chunk 逐字可见；相位全链 start×3/verdict×2/retry×1/adopted×1——第 1 轮被复刻判据 BLOCKER、带反馈重写后第 2 轮过审落库（"象牙塔求道"12 章）。103/103 测试、vite build 绿。
- **遗留→已修**：PLAN 会话回放按书聚合把相邻任务的开始事件也带出来（多次重跑被误读成"一直失败"，用户实踩）——已修：seedSession 用载荷带 taskId 的任务级事件框定本任务事件区间（首事件 id 为下界），并把 slice(-12)（DESC 列表上取错头，最新事件如"卷纲落库"被截掉）改为 slice(0,12).reverse() 时间正序展示。

## LLM 接入落库+加密+超时双保险（2026-09-27，未提交，实弹全通）

- **卷规划卡死根因（任务 #49 永久 RUNNING）**：MiniMaxClient 非流式 post() 走的 RestClient 虽然 factory 上设了 readTimeout，但 JDK HttpClient 的 request timeout **不覆盖响应体读取阶段**——响应头到、body 中途断供时 worker 在 HttpResponseInputStream.take() 无超时 park（jstack 实锤），取消标记也救不回（只在步骤间检查），每卡一次永久吃掉一个 novel-worker。修复：post()/embeddings 全部改 sendAsync + future.get(readTimeout) + 超时 cancel(true)（超时/中断都能打断底层读取），异常沿"传输失败重试一次→error 落库→LlmException"既有语义；LlmClientConfig 撤 RestClient 只留共享 HttpClient。
- **LLM 接入落库（V33 llm_providers 表）**：素材库·平台配置·「模型接入」页签全 CRUD+连通测试（POST /{id}/test，独立 HTTP 不经 MiniMaxClient）。api key **AES-256-GCM 密文落库**（SecretCipher：随机 IV，base64(iv||ct+tag)），主钥 novelgen.llm.master-key 在 application-local.yaml（gitignore，与库分离——拖库不得明文）；接口只回掩码（sk\*\*\*\*\*\*尾4），明文永不回传不落日志。LlmProviderResolver：llm_providers 启用行优先（单活约束：启用新行自动停用旧行；行未配超时/模型逐字段回退 yaml），无启用行/解密失败回退 yaml 静态配置（fail-open，老环境零变化）。MiniMaxClient/MiniMaxEmbeddingClient 连接要素全部改走 Resolver，改库即生效无需重启。
- **验证**：103/103 单测（新增 SecretCipher 6 例/Resolver 3 例/ProviderService 5 例）；vite build 绿；实弹——建行后 DB api_key_cipher 无明文、API 回掩码、连通测试 HTTP 200（1.1s）、真实生成走 DB 配置（sample_params 调用 ok 5.4s）。主钥 yaml 缩进坑：追加在 llm 块尾须 4 空格缩进（2 空格会挂到 novelgen 下导致"master-key 未配置"）；yaml key 带引号迁库时要剥引号。
- **坑固化**：MP lambda cache 解析不了 isDeleted 布尔字段——新表 DataService 的 Wrapper 用字符串列名（QueryWrapper 非 LambdaQueryWrapper）。
- 卷规划任务 #49 用户已点停止，重启恢复按取消标记收 STOPPED（设计行为），随时可重跑。

## 衍生复刻防线四件套（2026-09-26，未提交，实弹三探针全过）

- **用户报告的根因（书 9/10/11 源主角衍生实查定案）**：①世界观/设定卡=逐字克隆（设计语义）；②AI 大纲被"书名+样本标签+整段克隆世界文档"锚定成**换名复述**（书 11 大纲=源主角逐拍复刻，净观台词都是源配角甲原话）——衍生参数（water/pov/章数）只管文风密度与视角，没有一个参数碰剧情同一性，怎么改配置都一样；③前端 `buildOutlinePayload()` 丢 cloneAssets，AI 大纲路径勾选全部无效（后端 null=全克隆）；④"创建作品"按钮引用不存在的 `payload` 变量（一击即溃的死代码）。
- **修复四件**：①前端补传 cloneAssets + `plotOutline` 默认关 + createNovel 改用 buildOutlinePayload（直接路径保住 active 语义）+ 修正"样本骨架参与生成"的误导文案；②**大纲原创性把关**：新节点 `derive_originality`（system/user 入 PromptCatalog，启动自动落库）——sampleId 有书级骨架时生成后必评审（喂骨架+★2+ 原书人物名），判复刻（换名对应物/主线同序同构/桥段搬用）→ derive_outline/rewrite 带原因重写 ≤2 轮 → 轮满仍复刻任务 FAILED 带建议；评审故障 fail-open 放行但 warn 留痕；③activate() 骨架门禁：canon 大纲以 "> 由样本《" 开头拒绝激活；④卷规划审校注入 `derive_no_copy` 段（含样本骨架，复刻=BLOCKER），审校清单升五查。契约已回写 pipeline-contracts.md §五流 D。
- **验证（四层证据）**：单测 89/89（新增 NovelServiceOriginalityTest 五例：直通/无骨架直通/判复刻重写过/轮满失败/评审故障放行）；vite build 绿；重启后提示词注册表 78 条（+4）；实弹三探针——A（源主角锚定题，书12）：评审 1001 判复刻（理由精确命中"换名对应物+桥段 preserved"）→重写→1004 复审过，终稿为火葬场拾骨人全新故事；B（原创题，书13）：一次过；C（骨架书14）：activate 被 A0006 拦截。llm_call_log node=derive_originality 全程可回放。
- **已知边界**：直接创建路径勾 plotOutline 建成 active 书不经门禁（显式勾选=用户自担，契约已记）；derive_config 无 cloneAssets 回显，resumeDraft 恢复不了勾选态（克隆已发生，改了也无意义）。

## 提示词全量接库收尾：零骨架、零漏网（2026-09-25，未提交，feat/prompt-registry-full）

- **单一来源重构（用户复查定调"业务代码不许有提示词文本"）**：PromptTemplateService 的 get/format/getSection 改为免回退参数签名——回退一律由 PromptCatalog.contentOf(node,phase) 单点提供（Holder 惯例建索引，重复条目类加载即炸；目录缺条目 fail-fast）。删除显式回退旧重载（同时消灭 format 第三参 String 与 varargs 的歧义调用风险）。**业务代码零提示词文本**：sweep 证据——"你是"只在 PromptCatalog，service/runner 无 %s/%d 文本块残留。
- **本轮清掉的内联提示词文本**：OutlineService（章纲 system+user 巨块）、SceneService（门禁重写拼装）、ReviewService（READER_SYSTEM/SYSTEM 两常量+读者评审/审校/恢复扩写/两修订 user 全部）、DigestService（STATE_SPEC/STATE_SYSTEM 常量+digest system 巨块）、ChapterPipelineService（章修订 user 巨块）、VolumePlanService（卷规划/卷审校/单章重写三块巨模板+system×3+span/budget 四段回退）、VolumeReviewService、SampleParseService 七处、NovelService（derive_outline 三段）、ContextPackerService（STYLE_REDLINES 常量+开篇红线/双范例/四账本/复盘/卷规划框架全部回退）、LlmJson（喂回句）、MaterialCardService（设定卡块头）、SmokeRunner（冒烟任务+范例标题）。
- **又修两处内容漂移（目录行落后于代码意图，运行时一直在跑旧文案）**：① derive_outline/user 缺"全新原创/禁止复刻剧情线"硬化口径且多一个孤儿 %s——向导 AI 大纲每次都格式化失败走回退；② sample_params/user 缺 tags 推荐字段——"AI 帮我定"从未真的推荐过标签。均已按代码最新意图回写目录（sync 自动 version+1）。反向漂移一处：volume_plan_review/user 内联回退还是衍生豁免前旧口径（运行时目录已是新口径），内联删除即消。
- **验证**：mvn package+vite build 绿；84/84 单测（新增目录缺条目 fail-fast 用例）；重启同步 **74 条**（+material/card_block_header、smoke/exemplar_header、smoke/user）；实弹第 2 章审校 pass；零"回退/缺条目"告警。
- **前一批（同日早些）**：漏网补登记 outline/system、world_state/user、reader_fix/user_recover、scene 工艺三段、四账本段头、retro/volume_world/volume_seed(+empty)、json_retry_feedback、outline/reject_suffix；拼装段接线 scene_revise/user、reader_review/user、ai_review/user、digest/user(+time_anchor/ledger)；修 volume_plan/user 静态规则4 漂移（章长带从未进过提示词）+ askPlan feedback 死参（审校失败原因从未喂回，新增 plan_retry_feedback）；CRUD 补漏（custom 行可改、去 mapper `AND exact=TRUE` 门禁、PUT /prompts/{id}/enabled 启停、phase≤32）；V32 清退 4 条骨架行软删 + llm_node_config 补 12 节点（24 行对齐 LlmNode）；前端提示词页签（编辑解除 exact 门槛、format/{key}段 形态标签、启用开关列）。

## 提示词注册表全量落库（分支 feat/prompt-registry-full，2026-09-25）

## 提示词注册表全量落库（分支 feat/prompt-registry-full，2026-09-25）

- 用户定调"提示词一个不剩落库+完整增删改查"。新分支 feat/prompt-registry-full（自 09/0922 分支 f51ff87 开出）。
- **getSection 段读取**：PromptTemplateService 新增 {key} 占位段读取（库值优先+fail-open 回退），运行时拼装段从"仅浏览快照"升级为"接库生效"。PromptCatalog 新注册 13 段：scene_draft 的 style_redlines/derive_pov/derive_density/derive_tags/derive_redline、common 的 derive_volume/plan_span_free/plan_span_target/plan_budget_with/plan_budget_without、digest 的 state_spec。
- **消费点改造**：ContextPackerService（STYLE_REDLINES/deriveSection 四段）、DigestService（stateSpec）、VolumePlanService（span 自由/目标口径+预算带有/无带四段）全部走注册表。
- **增删改查补齐**：POST /api/prompts（新建自定义段 custom=true）/DELETE /api/prompts/{id}（软删，仅自定义行可删；目录同步不覆盖）；查/改原有。前端提示词页签：新建对话框（node/phase≤16/标题/内容）+自定义行删除按钮+「已改」标记。
- **V31**：prompt_templates.phase varchar(16)→32、node→64（新段名 plan_budget_without=18 字符超长曾致启动失败）。
- **AI 大纲材料边界修正**（衍生语义二修）：样本剧情骨架彻底退出 AI 生成材料（骨架只用于预填路径）；世界约束注入改为**用户勾选驱动**（cloneAssets.world 勾=同世界衍生注入，不勾=完全自由创作）——选择权在前端按钮，后端不硬编码。两模式实弹：勾=同世界新故事（制香/空嗅），不勾=自由创作（雾陵市都市深海系），均无原书桥段。
- 83/83 单测、vite build 绿；实弹：11 段落库、新建/删除/替换渲染全验。
- 坑实录：AGENTS 块#1 heredoc 反斜杠再次三连（正则 、
 转义、三引号嵌套）——全部回归 Edit 工具修复。**教训固化：改代码只准 Edit。**

## 审校衍生豁免 + 会话完成可回看（2026-09-25，未提交）

- **审校口径修正（衍生语义落地）**：volume_plan_review 清单原把"人物不在卡中"一刀切 BLOCKER——衍生新故事必有新主角，与衍生业务根本冲突（书 test/test2 连续 3 轮全败实证）。已改：新创主角/次要人物只要力量体系/背景与世界观自洽即不算硬伤，与既有人物/设定**矛盾**才是。新口径下 #43 一次过审：13 章卷纲落库（源主角衍生-test2）。
- **会话完成可回看**：队列行「会话」按钮从仅 RUNNING 放宽为非 QUEUED 全状态——DONE/STOPPED/INTERRUPTED 任务完成后可打开会话回看过程（事件本来就在 pipeline_events 落库，只是入口没了）。PLAN 会话回放（事件流水→轮次+原因条目）与 CHAPTERS trace 打底均支持。
- 数据本就持久（pipeline_events + llm_call_log），本次只是把消费入口补上。

## 卷纲过程透明化（2026-09-25，未提交，用户实战怒斥"生成过程不透明"）

- **实战暴露**：用户开 PLAN 会话视图看卷纲规划——永远"暂无转录事件"。代码级根因：seedSession 显式 `row.kind === 'PLAN' → return`（透明化批次只做了 CHAPTERS 的 trace 打底，PLAN 被排除），而 volume_plan 的 SSE 实时处理一直存在——用户中途打开看不到任何历史，只有之后的事件才可见。
- **修复**：seedSession 支持 PLAN——从 GET /api/llm-logs/events?novelId= 拉事件流水，过滤 volume_plan/volume_plan_review 最近 12 条映射为会话条目（开始/第N轮重写(审校原因可展开)/放弃/落库），SSE 增量照常实时追加。
- **止血实录**：书 9 的 #41 跑的是矛盾材料（canon 大纲仍为旧渔镇版，用户未走激活流程换新大纲）——必败，主动停止止损；PUT story 把重生成的婆罗门版大纲（2954 字）写入 canon；重提 #42（一致材料）RUNNING 中。
- 查看渠道全图：会话视图（回放+实时轮次）/规划页进度块/工作台队列/调用台账。
- 教训：透明化批次"只做章级管线"的边界划错了——卷纲/规划类任务的会话可视性应随任务化（⑤）一起交付，而不是留给下批。

## 卷纲失败根因修复：AI 大纲贴合克隆世界（2026-09-25，未提交，用户实战暴露资产矛盾）

- **实战炸雷**：书 9（源主角样本衍生）卷纲 3 轮全败。审校打回原因（llm_call_log 可回放）：卷纲人物（容晦/姑母/霁/沉舟/衍真）全不在设定卡中，设定卡的源主角/源配角甲/源配角乙无一出场——**根因是资产矛盾**：克隆的世界观+素材卡来自源主角原书，AI 大纲却是自创的渔镇新故事，卷规划把两者一起喂审校，必打回。审校 AI 自己都推理出"either the reference materials are completely wrong, or the outline is inventing characters"。
- **治本**：draftOutline 注入本书已克隆的 canon 世界观+素材卡（新 TemplateDef node=derive_outline/phase=world，exact=true 可前端编辑，%1$s=世界观 %2$s=卡名单；novelId 为空即未克隆时不注入），提示词明令"新剧情必须发生在克隆世界内，可新创主角但体系/地理/既有人物关系不得矛盾"——衍生语义落地：用原书的世界写新故事。
- **实弹**：书 9 重生成大纲 2954 字——新主角闍多罗、婆罗门净修林/吠陀/沙门/云中圣城（呼应样本设定），全新剧情线（家族七代比丘证悟前发狂之谜）——世界贴合且剧情原创。用户确认后激活，卷纲规划即可过审。
- **规约教训（用户怒批）**：曾把世界约束提示词内联在 NovelService——违反提示词注册表惯例，已迁 PromptCatalog（world/derive 段），业务代码只组装参数。另 AGENTS 坑 #1（heredoc 吃反斜杠）本轮三连踩（NUL 字节、\n 转义、字符串断裂）——改代码必须用 Edit 工具，python heredoc 补丁禁令重申。
- 顺带修复：规划页卷纲进度内嵌（PLAN 任务轮询+防重复提交+完成自动刷新）；ImportRunner insert 签名连带补参。83/83 单测、build 绿。

## 卷纲规划进度内嵌规划页（2026-09-25，未提交，用户问"卷纲生成进度在哪看"）

- 现状确认：auto 模式「AI 规划下一卷」早已任务化（auto-plan-async 秒回入队，队列 kind=PLAN），但进度只能切工作台看——规划页本身无展示，用户等不到反馈重复点（实战暴露）。
- PlanningView 内嵌进度块：卷纲页签顶部轮询 /api/pipeline/queue 过滤当前书 PLAN 任务——「卷纲规划中 · 第 N 章起」+进度条+完成态提示；进行中禁用重复提交按钮；任务从队列消失自动 loadAll 刷新卷纲；onUnmounted 清轮询。
- 查看渠道全集：①规划页内嵌进度条（新）②工作台队列 PLAN 行（卷纲规划中）③工作台 SSE 日志（volume_plan/volume_plan_review 的 start/retry/VERDICT 轮次判定实时事件）④调用台账（volume_plan_review 轮次明细）。
- 实弹：向导页重载拉新模块后进度条正常显示（「卷纲规划中 · 第 1 章起」+按钮禁用）；书 9 规划中实见 4 次 volume_plan 调用（审校重写轮咬合中）。vite build 绿。

## 生大纲即落库：向导草稿态改造（2026-09-25，未提交，用户实战指出"生大纲了书还不落库"）

- **产品语义修正**：书从"最后一步才创建"改为"点 AI 生成大纲即静默落库（status=draft）"——大纲生成期间页面关闭/浏览器崩溃，设定与大纲任务都不丢；书籍管理页立即可见可管理。
- **V30**：outline_draft_tasks.novel_id（任务挂书可追溯）；novels.status 放入 'draft'（V1 无 CHECK 直接用）。NovelCreateVO 加 draft/novelId；create 落库带状态；POST /api/novels/{id}/activate（draft→active 条件更新）；outline 任务链全栈带 novel_id。
- **向导**：Step2 点 AI 生成 → 静默建书（提示 ID）→ 书名框锁定（改名去书籍管理）→ 提交任务；Step3 按钮变「完成并激活」→ 保存大纲到 canon + activate。**草稿恢复**：进向导页检测 draft 书 → alert「继续这份草稿」→ 从 derive_config 反推全部设定 + GET /{id}/outline-draft/latest 自动取回已生成大纲/继续轮询。
- **实战 bug 修复**：大纲 worker 里 requireTitle 全站查重 → 草稿书撞自己的名字任务 FAILED（message「已有同名作品：草稿落库验证书」）——draftOutline 改为仅非空校验。
- **实弹**（浏览器全链）：向导生大纲 → 「书已落库为草稿（ID 8）」→ 书籍管理页立现草稿书（状态列 tag）→ 切页回向导 → 「继续这份草稿」恢复设定 → 重试生成 → 2802 字大纲自动填入 → 完成激活（status=active、大纲进 canon misc/大纲）。83/83 单测、build 绿。
- ImportRunner 两处 novelData.insert 调用补 status 参数（签名变更连带）。

## 书籍管理页（2026-09-25，未提交，用户定调"给开的书做增删改查列表页"）

- 新路由 /books + BooksView.vue（侧边菜单「书籍管理」）：全量作品表格（ID/书名+当前标记/简介/章数/审批模式/无人续跑[开关+目标章数]/创建时间/操作），「＋ 开新书」跳向导，「打开」=设当前书+跳工作台，双击行同打开。
- **删书欠账收口**：DELETE /api/novels/{id} 软删（is_deleted，psql 可恢复）。守卫两层——①existsActiveForNovel 有 QUEUED/RUNNING 任务拒绝并人话提示先停止；②autoContinue 开着时先改 derive_config.autoContinue=false + 链置 OFF（防删除后队列钩子继续给该书排任务）。前端 ElMessageBox.prompt 输完整书名确认；删当前选中书自动切到列表第一本。
- **改名/简介**：PUT /api/novels/{id}（改名全站唯一校验排除自身；提示用户改文风/门禁/衍生参数去各自专页）。
- 列表读数扩展：NovelVO 加 createTime/autoContinue/targetChapters（derive_config 摘要，每本一次轻查询）；NovelDTO 补 createTime（listAlive SQL 同步）。
- 浏览器实弹：列表渲染含「当前」标记 ✓；编辑简介保存回显 ✓；删除系统测试书丁（autoContinue=True）→ 库内 is_deleted=t/auto_state=OFF/derive_config.autoContinue=false、列表 5→4 本 ✓。83/83 单测、vite build 绿。

## 开新书独立页面（2026-09-24，未提交，用户定调"别跟工作台混一起"）

- 新路由 /wizard + WizardView.vue：向导四步从 WorkbenchView 的 640px 对话框整体迁出为 900px 独立页面（侧边菜单加「开新书」入口，工作台「＋ 开新书」改跳转）。业务方高频主操作升为一等页面，工作台回归监控台职责（队列/SSE/续跑/评审标准/衍生参数编辑）。
- WizardView 自足：initWizard 页面进入即重置（原 openWizard 无弹窗化）；完成页加「去工作台」按钮；AI 大纲异步、AI 帮我定、mobi 导入、样本预设断链修复全部随迁。derive 参数编辑器属书级操作留在工作台。
- 浏览器实弹：菜单/路由/四步渲染/字数带展示/衍生设定全控件 ✓；vite build 绿。WorkbenchView 向导符号全量切除（residual 对账仅剩跳转路径字符串）。

## AI 大纲草稿异步化（2026-09-24，未提交，用户实战暴露同步阻塞）

- **用户实战打脸**：同步 HTTP 版 AI 大纲草稿在业务方手里 5 分钟不返回（高峰/大上下文/长样本设定），且"要跑几百本书"同步阻塞完全不可扩展——设计错误当轮承认当轮改。
- **V29 outline_draft_tasks**：入参快照 JSONB + status（QUEUED/RUNNING/DONE/FAILED）+ result 正文；tuning outline_draft_parallel=4。
- **OutlineDraftService**：独立并发池（4 worker）消费；POST /api/novels/outline-draft 改为提交秒回 taskId（实测 **130ms**）；GET /outline-draft/{id} 轮询状态/结果；重启 RUNNING→QUEUED 自动重放（入参快照在库）。
- **前端**：点击秒回提示"后台生成中"，按钮转"大纲生成中…"但向导不阻塞（可继续填/连续提交多本）；轮询 3s，DONE 自动填入大纲框，FAILED 显示原因可重试；关向导不挂状态。
- **实弹**：两本连续提交 130ms 双双秒回，后台并发 35 秒双 DONE（3141/3586 字）。83/83 单测、vite build 绿。
- 顺带：源主角文风预设已采纳（#16 源主角·文风v1，章长带 2700-2850）；向导衍生设定步选无预设样本时显性提示+「提取文风预设并使用」一键；素材库导入行加「提预设」——消灭"深度解析完但开书下拉选不到文风"断链。

## mobi/azw3 电子书导入支持 + 《源主角》3.9 万字实弹（2026-09-24，未提交）

- **MobiExtractor**（common/util，MOBI6/PalmDOC 变体）：PalmDB 头解析 → DRM 检测（encryption≠0 报人话"带 DRM 请转 txt"）→ 逐记录解压 → trailing bytes 剥除（extra_flags 0x14 之类）→ HTML 转纯文本（style/script 块剥除+块级标签转行+实体解+C0 控制字符清零——NUL 不清 PostgreSQL 拒收 0x00）。压缩变体 HUFF/CDIC（多见 AZW）不支持，人话报错。PalmDOC 解压与 trailing 剥除纯函数 7 单测（literal run/LZ77 重叠回抄/multibyte/varint 语义——调试中修正三处实现错误：LZ77 距离需 &0x7FF 剥标志位、trailing entry 的 varint 在条目尾部而非头部、style 内容残留）。
- **接线**：POST /api/preset/analyze 收 mobiBase64（text 空时走提取，>64MB 拒）；前端两导入入口（向导+素材库「导入新小说」）accept .txt/.mobi/.azw3/.azw，FileReader 按扩展名分流 readAsDataURL→base64。analyze/from-sample 语义不变（from-sample 不支持 mobi，走 analyze 落语料后照常采纳预设）。
- **实弹（《源主角》作者甲 3.9 万字 mobi 395KB）**：analyze 14 块/38771 字/章长带 2700-2850，与三品类相似度 0.31-0.34 判别正确（建议建新品类）；FAST 解析 13 章 DONE、70 卡；**大纲起承转合全对**（婆罗门→沙门→佛陀→入世沉沦→渡口证悟，人物零错）；★3 人物分层正确（源配角甲/源配角乙/源配角丁/小源主角）；世界观正确识别古印度婆罗门文明；**标签 13 枚**（文学小说/宗教哲学/精神求道/成长小说/东方哲学/中短篇/慢节奏/婆罗门/佛陀/沙门苦修/轮回证悟/河流渡口/吠陀经典——与网文品类判然有别）；手动重提端点复验通过（另一组等质量标签），列表 tags 回带正常。
- 坑实录：①PalmDOC 直出 NUL 未清 → preset_corpus 落库 "invalid byte sequence 0x00"（PostgreSQL 拒 0x00）→ htmlToText 清 C0 控制字符；②String.replaceAll 不收 lambda（Matcher 循环替）；③单测 byte 字面量 >127 需强转。测试 83/83 绿。

## 开书向导「AI 生成大纲草稿」（2026-09-24，未提交）

- 新节点 derive_outline（创作型 temp 0.8，LlmTemps 登记模板入 PromptCatalog）+ POST /api/novels/outline-draft（NovelService.draftOutline，纯生成不落库）：书名/简介/文风预设 + 衍生设定（样本骨架截断 2200、类型标签、POV+主视角、节奏口径、目标章数/每卷章数→自动估卷数）→ 大纲 markdown（五节：主题悬念/主线起承转合/分卷走向带卷尾钩/主要人物/题材基调）。样本骨架明令"只借结构与节奏气质，禁止搬运专有人名/地名/设定"。
- 前端向导 Step2「全书大纲」顶部加「AI 生成大纲草稿」按钮（需书名+预设；已有内容覆盖前 confirm）。
- 实弹：《系统测试书丙》（深海系预设+样本 3+混搭标签）3399 字草稿——主视角名"沈临秋"入书、混搭标签全体现（深海系元素/探案单元剧/东方封雾人血脉）、120÷10 精确 12 卷、样本专有设定零搬运；记账 5.5K tokens/23 秒。
- **坑（再证规约）**：NovelService 内联 FQN 把 PromptTemplateService 包名写错（llm. 实为 service.）——增量编译竟然放行、启动装配才炸（ClassNotFoundException）；clean compile + 全量 grep 内联 FQN 清零后修复。规约"16 文件内联 FQN 清零"防的就是这个：FQN 写错包增量编译可能漏抓，启动实弹才现形。
- 验证：clean compile、76/76、vite build、实弹见上。

## 衍生自由度扩展：类型/特征标签体系（2026-09-24，未提交）

- **两层标签**：样本层——AI 深度解析自动提取原书类型/题材/体量节奏/标志性特征元素（V28 imported_samples.tags JSONB，新节点 sample_tags，解析管线 world 后自动跑 + POST /samples/{id}/tags 手动重提）；衍生层——derive_config.tags（零迁移），用户在向导衍生设定步/工作台「衍生参数」对话框沿用样本标签（multiple allow-create + 「沿用样本标签」按钮）或自由自定（上限 20 个、单个 ≤12 字）。
- **消费接线（与 POV/密度同通道，fail-open）**：ContextPackerService.deriveSection 加【类型标签】段（volume 与 scene 两处提示词都注入）；sample_params「AI 帮我定」推荐顺带建议 tags。
- **实弹**：深海系样本真实提取 13 标签（深海系/宇宙恐怖/短篇集/哥特/旧日支配者/深潜者/修格斯/拉莱耶/疯狂山脉/梦境入侵/血脉诅咒/邪教祭祀/古神复苏——三类口径齐）；novel 6 写自定混搭标签、novel 7 写纯自定标签落库正确；**novel 6 规划第 2 卷实锤标签注入 volume_plan 提示词**（request_json 含【类型标签】段 + 拉莱耶/剑与魔法/狼人三探针全命中），8 章卷纲顺带落库。
- 验证：编译/76 单测/vite build 绿。前端列表标签列/提标签按钮/向导标签编辑/对话框标签全部随批。

## 全流程前端暴露收口：六入口缺口补齐（2026-09-24，未提交）

- 用户对表三步需求发现"后端能力全在、前端入口有洞"，本轮补齐：①素材库「导入小说」页签头部「导入新小说」按钮+对话框（上传/粘贴→analyze→列表高亮新行）——"先囤素材不开书"不再被迫走进开书向导；②向导分析完成区「去深度解析剧情与资产 →」按钮（SampleAnalyzeVO 新增 sampleId 回带，跳 /library?sampleId=N 行高亮）；③**GET/PUT /api/novels/{id}/derive-config**——衍生参数开书后可改（工作台头部「衍生参数」按钮+全字段对话框，老书由此启用无人续跑，《导入书A》实弹启用 IDLE 68/80 章；sourceSampleId 保留、autoContinue 开时仍强制 plan_mode auto）；④素材库风格包 gate_config 编辑区补「每章字数带」budget_min/max 输入（回显+保存，卷规划钳制口径）+向导衍生设定步展示预设字数带（PresetVO 扩 budgetMin/Max 从 gate_config 解析，listPresets 填充）；⑤工作台「启动生成」表单加优先级三档（/run payload priority）；⑥资产 drawer「新建卡」（POST samples/{id}/cards，别名中英文逗号切分，AI 漏抽补录）。
- 实弹：预设字数带回读（SAO 2650-2850，历史预设 null 容忍）、老书 derive-config 读（全空 fail-open）与写（启用续跑）、新建卡落库别名正确、analyze 回带 sampleId=6；测试资产已清。编译/76 单测/vite build 绿。
- 遗留：六处新 UI 件未做浏览器走查（模板契约已验）；卷间复盘自动前置与长程续跑仍是待实测路径。

## 爆款资产化三批之 P2+P3：衍生参数面板 + 无人续跑闭环（2026-09-24，未提交，分支 `09/0922-走查与流式收尾`）

- **P2 衍生开书**：V26 novels.derive_config JSONB + generation_tasks.priority；开书向导三步改四步——Step1「衍生设定」（参考样本选择+克隆勾选[素材卡★2+/世界观/骨架预填大纲]、掺水量滑杆 0-100、POV+主视角、每卷章数/总目标、节奏说明、无人续跑开关、优先级、「AI 帮我定」）；POST /api/novels 收 sampleId/cloneAssets/deriveConfig，NovelService.create 同事务克隆样本资产（★2+ 卡 pinned=★3、world→canon、骨架→大纲预填带出处标注）+ 掺水量线性换算书 gate_config（DeriveSupport.waterGates：block 0.20-0.50/hard 0.35-0.65/floor 0.70-0.50）。
- **P2 提示词接线（全 fail-open，无配置书零变化）**：scene_draft 模板加【叙事视角】【情节密度要求】段（POV/主视角/掺水量三档文案）；volume_plan 的章数由 derive_config.chaptersPerVolume 给目标（提示+结构校验 ±容差收口，无配置走旧 6-15 自主）；review_blocker_replan 支持按书覆盖（autoContinue=true 即自动重写一轮）；新节点 sample_params（AI 推荐衍生参数，POST /api/preset/samples/{id}/recommend-params）。
- **P2 实弹**：深海系样本克隆开书（68 卡/世界观/大纲预填/derive_config 全落库，water=70→0.40/0.56/0.56 精确）；AI 帮我定一次过（识别多视角短篇连缀→多视角轮换+water5+理由）；chaptersPerVolume=8 → 首卷恰好 8 章一次过审（遗物/祭文/群星归位）。
- **P3 无人续跑闭环**：V27 novels.auto_state/auto_message/auto_volumes + 认领序 priority DESC,id ASC + tuning batch_max_chapters=10（M4 欠账收口）/auto_continue_max_volumes=50（保险丝）；GenerationQueueService——CHAPTERS DONE 钩子 continueChain（目标未达→有规划行续批 cap 钳目标/无规划行 submitPlan 下卷+卷数计数）、PLAN 前自动卷复盘（fail-open）、PLAN DONE 自动续批、终态非 DONE 链断 PAUSED 带原因（用户停/失败耗尽/规划失败）、REACHED 目标达成收链；端点 GET/POST /api/pipeline/novels/{id}/auto-continue[/resume]（resume 允许解卡：RUNNING 但无活动任务）；工作台「无人续跑」状态卡（目标进度条/暂停原因/恢复按钮，随 3s 轮询刷新）。
- **P3 实弹（全路径验证过，2026-09-24 凌晨）**：①链启动钳目标（target=1 → 续批 1-1）✓；②故障注入硬停→任务 INTERRUPTED+链 PAUSED 带原因 ✓；③恢复续跑→第 1 章全管线（含门禁修订轮拉锯）DIGESTED→**链自动 REACHED 收链** ✓；④无规划行的书开链→自动规划第 1 卷（6 章卷纲 2 分钟落库、卷数计数+1）→PLAN DONE 钩子**自动续批 1-6** ✓；⑤再停→PAUSED ✓；⑥链卡死（RUNNING 无任务）resume 解卡 ✓。演示资产保留：novel 6《系统测试书戊》（ch1 已 DIGESTED、REACHED 态）与 novel 7《系统测试书丁》（PAUSED 态，可体验「恢复续跑」）。
- **顺手修两个真 bug**：① ChapterMapper.maxVolumeNo 声明 primitive int，无规划行的书 MAX 返回 NULL 直接 BindingException（首个 resume 500 根因）→改 Integer+impl 判空；② 存量 bug：EmbeddingMapper.findMissingCards 只选 3 列而 MissingRow 构造器自动映射要 5 列——有素材卡未嵌成的书进 RAG 惰性索引必炸（fail-open 吞成静默降级），克隆卡书首次踩中→补 NULL AS chapterNo/summary AS content。
- **前端**：向导四步/续跑状态卡，vite build 绿；全量单测 76/76。

## 爆款资产化三批之 P1：导入小说深度解析全链路上线（2026-09-24，未提交，分支 `09/0922-走查与流式收尾`）

- **产品口径（用户定调）**：10 员工全员共享工作台（不做隔离）；双档解析（快速默认+可升级完整）；P1 资产化 → P2 衍生参数 → P3 无人续跑。部署运维用户自理。
- **V25 三表**：sample_parse_tasks（每样本一行活跃任务，QUEUED/RUNNING/DONE/FAILED/INTERRUPTED）、sample_plot_nodes（book/volume/chapter 剧情树，UNIQUE(sample,level,seq)=章行即断点 checkpoint）、sample_cards（kind 对齐 material_cards+world，relations/aliases jsonb，importance 1-3，mentions 首现章）。tuning 新键 sample_parse_parallel=4 / sample_fast_chapters=40。
- **管线**（SampleParseService，独立单线程 runner+逐章并发池，不碰生成队列）：语料块按 `书名·块N` 重组原文 → chapterSplit 纯函数（第N章回/序章楔子/第N卷界标；<3 章或覆盖<40% 回退伪章 3200 字，巨段再硬切）→ 逐章 sample_chapter（>1.2万字分段解析后机械合并；传输级失败重试1次再败留缺口，缺口>10% 判 FAILED）→ 实体机械归并（同名/别名并名）→ sample_merge LLM 模糊判组（>20 实体才跑，一次 ~3-5 分钟）→ 有卷标记逐卷 sample_volume → sample_outline 全书大纲 → sample_world 世界观卡。LLM 节点五枚全登记（路由行留空走默认）；模板入 PromptCatalog（36 条）；Stage 枚举 sample_parse 走 StageLog（novel_id 可空）。
- **断点续跑**：章行存在即跳过（FAST→FULL 升级自然增量）；重启 RUNNING→INTERRUPTED（ApplicationReadyEvent 善后）；FAILED/INTERRUPTED 前端「继续解析」。
- **端点**：POST /api/preset/samples/{id}/parse{mode}、/parse/resume、GET /parse（状态+资产计数）、GET /assets（剧情树+卡）、PUT/DELETE /api/preset/cards/{id}（人工纠偏）。
- **前端**：素材库「导入小说」页签升级——深度解析列（进度条/阶段中文/完成态章·卷·卡计数/失败 tooltip）+ 操作列（快速解析/升级完整[二次确认]/继续解析/资产）；65% drawer 资产四页签（剧情结构 el-tree 书卷章+beats 场景拆解表/资产卡分类表含纠偏编辑/关系平表/世界观）；活跃解析 3s 轮询自动启停。
- **MiniMax 探针实录**（var/probe_sample_parse.py + probe_report.json）：10 万字（5.6 万 tokens）单次进模型无墙；并发 4×20 次零限流、吞吐 ~4.9s/章；JSON 破损全因自设 max_tokens 打顶（生产无路由行=不带=服务端默认，think 段由 extractContent 剥）；单章 ~7.2K tokens（reasoning 占 65%）。
- **实弹（深海系 24 万字，sampleId=3）**：FAST 40 章 ~7 分钟 → 482 卡；升级 FULL 中途 taskkill → 重启 INTERRUPTED → 续跑 → 全书 77 章完整 DONE 933 卡/1307 关系边/大纲 1364 字；质量抽查（南极科考三主角 ★3 分层正确、世界观去抽样标注、beats 真拆场景）；成本 77 万 tokens ≈ 2-3 元/本，500 万字长篇推算 40-60 元/2.5-3h。测试资产保留可看（员工演示样本）。
- **顺手修的坑**：Write 工具曾把两个 NUL 字节写进 map 键分隔符（file 判 data、Edit 拒收）——python 字节级替换为 `·`；伪章巨段（整章一行 1.3 万字）二次硬切；splitBySize 换行归前段尾部；章跨卷界标时卷号取章开始时点。
- **P2 预埋决定**：克隆开书默认只带 ★2+ 卡（FAST 产物 481 张全克隆会撑爆规划上下文，fullBlock 无上限）；卷纲参考/大纲预填随 sample_plot_nodes.book 走。

## 导入小说台账：输入即入库 + 分析快照全留档 + 素材库专门分类（2026-09-23，已提交 `6d5d9e7`）

- **V24 imported_samples**：每本导入小说一行（title/genre/块数/字数/来源/**analysis 快照全文 jsonb**/preset_id 回链），存量三品类自动回填（source=backfill，无快照标"历史导入"）
- **analyze 双写**：preset_corpus 切块 + imported_samples 台账行（完整分析 JSON：指纹基线/章长带/相似度列表/建议/notes）；**adopt 统一回链**（品类采纳出的预设写回该品类全部样本行，所有采纳路径生效）
- **素材库·质量与风格·「导入小说」专页**：列表（小说名/入库品类/块数/字数/来源/采纳预设/时间）+ 展开看当次分析快照；GET /api/preset/samples
- 坑：MP BaseMapper 直插 jsonb 列报 varchar 类型错——本仓库 jsonb 写入惯例是自定义 XML `#{x}::jsonb`（JacksonTypeHandler 只管读），照做后通
- 实弹：干净闭环（分析→台账行快照在库→采纳→回链预设 #13）；三真实品类补链（漱石猫#3/深海系#4/刀剑#8）；浏览器专页渲染核验；测试资产已清
- 遗留观察：analyze 中途失败会把已落的语料块留下（品类名占用 → 重试得 ·2/·3）——接受为"资产不丢"语义，失败场景由用户在素材库清理

## 分析即落库：导入语料一律存为可复用资产（2026-09-23，未提交，用户定调"功能都要落库"）

- **analyze 不再是纯探针**：切块即写入 preset_corpus，品类名唯一化（占用则 ·2/·3，入口限长 60 保总长 ≤64；纯函数+单测）。响应 VO 的 suggestedGenre 改为 genre（落库名），notes 首条注明"语料已存为品类「X」，之后可补料/重提/采纳"
- **from-sample 双路径**：品类已有语料（analyze 已存）→ 直接采纳；无语料但带正文 → 切块落库再采纳（直连 API 兜底）。原"已有语料即拒"的防混卫兵随之取消（防混靠品类名唯一化）
- 向导 UI：品类名输入框改为标签展示（锁定落库名），预设名仍可编辑；正文框文案去掉"不保存"
- 实弹：两次同名分析 → 「落库验证样本」+「·2」各 5 块并存；from-sample 无正文直接采纳 → 预设 #11（gate_config 自动带章长带 900-2500）；测试资产已清
- 库内语料现状：深海系怪谈 75 块 / 未解析长样本 1545 块 / 漱石猫风样本 5 块，全部可复用

## SAO 预设端到端验收 + 预算带接线 + 深度测试批（2026-09-23，未提交）

- **缺口修复：预设章长带接进生成链路**。此前预算带只在分析页展示，卷规划提示词让 LLM"参考往卷 2800-4000"自估。现在 adopt() 把 band 写进 gate_config（budget_min/max），VolumePlanService 按书读带：提示词改为"必须落在带内"+ 解析后钳制。实测 SAO 书首卷 9 章预算全部 2650-2850 落带（预设 #8 的 band 经 SQL 补写）
- **端到端验收通过**（刀剑测试书 id=5，保留可读）：开书（预设#8 克隆）→ SAO 风大纲 → 首卷规划 9 章（死亡公测/镰刀阴影/第五层终战，贴大纲）→ 生成 ch1 → **DIGESTED**。实际 2967 字（预算 2700-2800×tol0.1=上限 3080 内），reader/ai_review 双过，正文轻小说味真实（短行对话、孤狼剑士、百层浮游城），与漱石猫文风可感区分
- **真发现①门禁拉锯**：章级 line_avg_len 基线 46.19±0.26（下限 34.2），模型首稿 14.77/次稿 28.53（轻小说短行连打）连拒 2 轮+自愈重试，第三轮 56.08 进带通过——指纹门禁真咬合，但也暴露**自动提取的异风格预设对模型偏紧，收敛靠自愈梯子**（全章 13 次调用 32 万 tokens，约 35 分钟）。待后续：预设应用时按模型能力放宽 tolerance 或按书微调向导
- **真发现②边界与按钮补测**：空文/纯空白/纯数字→干净 PARAM_ERROR；一句胡话→低置信不拦但预算 0-0（adopt 后 band 判 lo>0 失败自动回退旧口径，安全）；纯英文→零指标判不可比；向导「建品类并使用」按钮浏览器首点全通（导入书B样本判差异大 25%→建类→选用），测试品类已清
- **环境事件**：今晚后端与 vite 各无声暴毙一次（无日志），重启即好；重要测试前先探活。auto-plan 正确路径是 /api/novels/{id}/**planning**/volume/auto-plan
- 遗留：删书无 UI（测试书只能 psql 软删）；line_avg_len 这类双侧带指标在 UI 上不可见，作者不知道为什么被拒

## 未解析长样本全 27 卷实弹：量级与判别度双验证（2026-09-23，未提交）

- **量级口径兑现**：511 万字 txt（15MB JSON body）全量 analyze **1.2 秒**（1545 块 / 424 万字 / 12 项指标），from-sample 建品类 3.8 秒——"用户导入多少就是多少"成立
- **判别度**：SAO vs 深海系 0.39 / vs 漱石猫 0.18 → 正确建议建新品类；一键建品类「未解析长样本」→ 预设 #8；复分析自家 **1.0 自匹配**，闭环成立（下次同风格直接建议复用）
- 插曲：测试中后端一次**无声暴毙**（无 OOM 无 shutdown 日志，8090 消失）；重启后同款 15MB 请求秒过——非请求体所致，原因未明，观察中。教训：重要测试前先探活
- 附件定位坑：用户附件在 Downloads 子目录时，只 ls 根目录会找错对象（上一轮把旧截图缓存当成了附件）

## 开书向导·导入小说分析 + 一键建品类（2026-09-23，未提交）

- **向导第一步双模式**：「选现有预设」/「导入我的小说分析」（粘贴或上传 txt，最多 800 万字）。分析纯机械零 LLM 不落库：自动切块（段落边界 ~3200 字收口、剔除纯数字行、碎尾块并前块）→ 指纹基线 + 章长预算带 → 与现有品类逐个算相似度
- **相似度数学**（纯函数+6 单测）：共有指标按 相对差/合成容差 打分取均值，按共有占并有的比例开方打折（死指标单侧存在=风格差异信号）；共有 <3 判不可比。判读：≥0.65 建议复用 / <0.45 建议新品类 / 中间两可
- **一键建品类**（from-sample）：切块落 preset_corpus + 幂等采纳为预设；同名品类已有语料则拒（防混入）
- **实弹判别度**：漱石猫正文 8 章 → 自家预设 0.73 / 深海系 0.31；深海系原书 24 万字 → 自家 0.93 / 漱石猫 0.30。浏览器全流程（载入 3.3 万字→分析→相似度条→相近建议→选用）通过
- 待后续批：题材/情节维度（LLM 层）、rulesMd 提取、范例库

## 前端体验整改第二批：开书向导 + 错误人话层（2026-09-23，未提交）

- **开书入口从无到有**：此前建书只能插库（NovelController 连 POST 都没有）——终极规则怪谈。现在工作台「＋ 开新书」三步向导：书名/品类预设（必选，无预设时引导去素材库提取）→ 大纲（可跳过，警告后果）→ 完成页三步指引并一键落规划页
- 后端：POST /api/novels（NovelCreateVO）→ NovelService.create：书名唯一校验 + 克隆预设为书私有风格包（StylePackMapper.insertPack，is_preset=FALSE，带 gate_config）+ 事务落书
- **错误码人话层**（api.js 全局注入，零视图改动）：A0006/A0007/A0008/B0001/B0002/C0002/C0003/D0001 → 错误消息后拼"下一步怎么做"指引
- 实弹：向导建"向导测试书/2"全流程（含大纲落 canon、自动选中、跳转规划页联动）通过后软删清理；vite build + mvn compile 绿
- 整改路线图余项：③章节页审校报告结构化呈现 ④术语词典统一（tooltip 集中管理）⑤LibraryView 拆组件

## 前端体验整改第一批 + 坏页修复（2026-09-23，已提交 `53c75cf`，已合 main `1446621`）

- **坏页（规划页）根因**：`watch(retro)` 写在 `const retro` 声明前 → setup 即 TDZ 崩溃整页白（09-16 修过一次，后续编辑把顺序又改反）。连带修复：复盘提案决策 URL 404（前端漏 `/novels/{id}/planning` 前缀，一直没通）、`v.chapters.length` 无守卫。浏览器实弹验证整页恢复
- **UX 快赢①**：工作台把「规划模式/审批模式」两个隐藏规则并排显性化（双开关+tooltip 人话说明），此前规划模式只在规划页可切
- **UX 快赢②**：素材库 11 个平铺页签分四组——素材设定 / 记忆台账 / 平台配置 / 质量与风格（接缝重组，无块搬运）
- **UX 快赢③**：复盘提案正文键名错配修复（读 desc/description，实际 schema 是 where/issue/suggestion → 拼出 "[major/pacing] null" 残行），库内 4 条残行软删
- **整改路线图（后续批）**：①开书向导（建书→选预设→大纲→首卷一条龙，消灭"没建风格包就提交"式死路）②错误信息人话层（后端 detail → 前端可读指引）③章节页审批流可视化（审校报告结构化呈现）④术语词典统一（tooltip 文案集中管理）⑤LibraryView 拆组件（1000+ 行巨型 SFC）
- 深海系语料《深海系的呼唤》9 篇→75 块导入「深海系怪谈」品类→提取 9 指标（比喻密度 1.8 vs 漱石猫 2.4-7，感叹号死指标剔除；章长带 3150-3350 系分块伪影，待按篇切分修正）→预设 #4「洛式恐怖·深海系v1」
- 工具沉淀：var/api-audit.py（前端 URL vs 后端路由全量对账，75/73 比对发现 retro 404）

## 阶段三第一块：品类语料 + 机械特征提取 + 预设（2026-09-22，未提交）

- **全扩展口径（用户定调：语料导入多少算多少/品类随时加/字数用户定）**：preset_corpus 表（V23）按品类自由命名存语料章；style_packs 加 is_preset——预设=未被书引用的风格包，应用到书=拷贝指纹/门禁/规则
- **机械提取**（零 LLM）：`GateService.computeMetrics` 批量跑语料 → 分位带宽（value=中位/tolerance=相对半宽夹紧 0.15-1.5/abs_max=p95×1.1，规模自适应：n<10 低置信不拒绝）；章长预算带 p15/p85 取整 50；提取数学纯函数化 + 4 单测
- 端点 /api/preset/*（genres/corpus CRUD/extract/adopt/list/apply）+ 素材库新「品类预设」页签（选择或新建品类→粘贴导入→提取草稿→过目→存预设→应用到书）
- **实弹**（用 ch59-63 真实正文作语料）：5 章导入→9 项指标提取（对话密度 13.36/比喻 3.35 上限 4.68——与手调基线 14-18/2.4-7 自洽）、低置信正确标注、预设 #3 落库、应用到《导入书B》gate_config 实变
- 待后续批：黑名单对照挖掘、rulesMd LLM 提取、范例库、LLM 判定指标（fat_ratio）抽样、开书即选预设
- 前序：审校硬伤处置已提交 `639ad1f`；VolumeReviewMapper `#{report.toString()}` 迁移期潜伏 bug 修复（未提交）

## 审校硬伤自动处置（2026-09-22，未提交）——无人值守闭环的最后结构堵点收口

- 复审仍 BLOCKER 不再必转人工：`review_blocker_replan`（默认 0=现状转人工；1=自动「清正文+换目标重写」一轮，仍不过才转人工）——reviewStep 返回 REVIEW_BLOCKED 新枚举，梯子统一处置（markReviewPending/reviewBlockedDisposition），三处尝试点全覆盖
- **顺带修潜伏坑**：既有梯子在拼章后失败走 replan 会撞 replanChapter 的「有正文即拒」守卫直接炸——replan 前统一 `clearFullTextForReplan`（saveFullText 置 null），replanChapter 自带的 resetForReoutline 负责清场景/门禁
- 验证：编译、52/52、重启上线零 ERROR、active 任务零（重启前核查顺序失误一次：status 查询因 cookie 过期失败仍执行了重启，事后对账确认当时空闲无损失——教训：**重启前的空闲核查必须成功完成才许继续**）
- 待实测：BLOCKER 处置路径需真实/构造场景验证（默认关闭不影响现状）

## ch66 一发三验全过 + 审校透明化（2026-09-22，未提交）

- **ch66 实测**（任务 #30，18 分钟，21.3 万 tokens，DIGESTED）：①修订流式实战——1997 chunk 帧、3 次 reset（=2 场景重写+1 章级修订，与 trace 吻合）、章级修订块 113 帧、think 流 23 万字；②伏笔归档实战——F20/F23/F25/F26/F27 全转 dropped，健康度 proposed 30→26；③自愈链完整（scene_revise×2 + chapter_revise×1 后过审）。实录：监控脚本首报 chunk=0 系解析 bug（SSE 行 `data:{...}` 无空格，正则带了空格）——工具误报与系统故障要分清
- **审校透明化**（用户走查发现"AI 审校黑盒"）：评审思考流实时转发（ReviewThinkRelay 只转 think——JSON 判定逐字流是噪音）、新增 VERDICT 相位事件（每轮 round/verdict/issues 摘要行）、DONE 事件带意见明细；LlmJson.ask 支持流式透传；前端日志/会话转录逐轮判定可展开意见、输出区新增「读者评审/AI 审校（思考）」折叠块。**待下次生成实测**
- **失败自动重试上线**：V22 迁移已应用（flyway t），重启后 queue VO 带 retryCount；真实故障触发待实战
- 后端 8090/前端 5173 运行中新代码；分支 3+3 提交（3 已推送，其余未）

## 量产阶段二第三块：失败任务自动重试（2026-09-22，未提交；ch66 生成期间并行开发）

- 章级自愈梯子尽后的**任务级自动重排**：失败分支（用户停/暂停/取消绝不重试）重排同任务行 RUNNING→QUEUED、从失败章断点续跑，`task_auto_retry_times`（默认 1，0 关闭）封顶；V22 加 retry_count 列，SQL 守卫 `status='RUNNING' AND cancel_requested=FALSE` 防竞态；TaskRow/VO 带 retryCount，重排发 QUEUED 事件
- 验证：编译、52/52；**V22 迁移与重启待 ch66 完成后执行**（坑#5：管线忙不许重启）；真实失败重试待实际故障实测
- 并行中：ch66（任务 #30）生成中——SSE 抓流 441KB+ 在录，后台监控将出报告（验证修订流式/F20 等归档/章产出）

## 量产阶段二第二块：伏笔自动园艺第一刀——过期提议自动归档（2026-09-22，未提交）

- digest 落账时扫描：proposed 停留超 `foreshadow_proposed_max_age` 章（默认 20，0 关闭，tuning 可调）未被规划引用 → 转 dropped，逐条 warn 显性化，素材库可改回 planned 恢复；健康度读数加 archivedCount
- 规则预演（真库，阈值 20）：下次 digest 将归档 F20/F23/F25/F26/F27 五条（22-24 章未被卷六规划引用的卷五遗留）；归档动作本身待 ch66 生成实测
- 验证：编译、52/52、vite build、后端重启 + 健康度端点带 archivedCount 实弹
- 前序：账本健康度批已提交 `e97a600`；流式收尾批 `a2eb4c6`（分支 3 提交未推送）

## 量产阶段二第一块：账本健康度（2026-09-22，未提交）

- `GET /api/novels/{id}/ledger-health` + 素材库伏笔页签顶部摘要条：待采纳数与最老停留章龄、埋设/回收逾期编码、事实账与世界状态覆盖进度——自动园艺的可见性地基
- 口径实录：时间基线初版用「章表最大章号」把未生成的规划行（66-68 NEW）也计入，F46/F67/F68 排期在 66-67 章被误报逾期——**改为事实账最新章（65）**后归零；真问题浮出：F20 自 41 章提议后停 24 章未采纳、30 条 proposed 堆积
- 验证：编译、52/52、vite build、真库实弹（上）+ DB 对账；路线阶段二下一块：伏笔自动采纳规则 / 失败任务自动重试
- 流式收尾批已提交 `a2eb4c6`（分支未推送）

## 流式收尾批：scene_revise/chapter_revise 接流式 + 会话断线重连重建（2026-09-22，未提交，分支 `09/0922-走查与流式收尾`）

- **修订流式**：`SceneChunkRelay.reset()` 重写信号（修订是替换不是拼接）——scene_revise/chapter_revise 全部挂 `chatStream`（开关同 `stream_long_text`）；章级修订走 sceneNo=0 专用块，前端题为「章级修订」；applyChunk 处理 reset 清旧稿
- **SSE 断线重连善后**：重连（onopen）触发重建——转录清空后按 trace 重新打底（seedSession 复用），输出区用章详情最终稿替换断线期间完成的半截流式块（错过的 SCENE/DRAFT 事件不可回放）
- 验证：编译过、52/52、vite build、重启零 ERROR；**修订流式与 reset 待下次真实生成实测**（生成中故意断网重连可验 ②）
- 阶段一剩余：浏览器走查修整（进行中）；存量 outline 台账回填维持不做

## 产品路线定调：量产平台（2026-09-22，`docs/design/量产平台路线.md`）

- 愿景：多用户按各自思路同时量产不同类别/节奏/文笔/题材/篇幅的高质量小说（基线 100 万字/天）；用户持有高质量原创语料作风格基线资产
- 实测账：单章 12.5 分钟/0.4 元，3 书道并行即超 100 万字/天——速度成本非墙，墙在无人闭环/记忆园艺/验收/限速
- 四阶段：①走查+流式（当前分支）→②量产闭环→③特征提取管线（语料→风格包）→④多租户；开放参数：语料量级待用户提供

## 全系统冒烟 57/57（2026-09-21，命名切换后）

- 覆盖：认证/作品/队列三态受理（A0005/A0006+detail）/章列表详情档案/规划全读/素材库 13 读口/LLM 台账四口/素材卡·canon·llm-nodes 全 CRUD（建读改删净）/tuning·gate-config 同值回写/提示词坏占位符 A0001 拒绝/规划行 999 建删/SSE 流——`var/smoke.py` 可复跑
- SSE 首测假阳性教训：响应头要等首次心跳（20s）才 flush，测试窗口须 >20s；断开 emitter 由 onError/onTimeout/onCompletion 摘除，无残留刷屏
- 未覆盖（触发真实生成/审批/LLM 计费）：run/approve/reject/veto/replan/regenerate/auto-plan/review/world-states backfill——待浏览器走查或真实任务时验

## 命名配对切换（2026-09-21，用户定调，未提交）

- **项目自有配对落地**：DTO=库实体/mapper 层、VO=web 层出入参（AGENTS.md 铁律已改写，覆盖阿里手册原配对）。49 类全库改名：22 实体 XxxDO→XxxDTO（model/entity→model/dto）、4 顶层收参+23 嵌套收参 XxxDTO→XxxVO（收参全在 model/vo 或 controller 内嵌）
- **文档对齐（A 方案）**：`docs/code-standards.md` 全面修订至新配对（§1/§2/§3.2/§3.6/§4/§5/§8.2，顺带修正 JdbcTemplate/旧 Result 形状等陈旧段），AGENTS.md 补该文档指针——此前 AGENTS 未指向它是历次会话漏读的根因
- 验证：三项残留 grep 清零、UTF-8 完好、编译过、**52/52**、JSON 契约零变化（前端无感）、重启实弹五链路全过；台账 §十二；**未 commit**
- 施工实录：python 脚本文件改名（500+ 处机械改名 Edit 不可行），第一遍漏 controller 嵌套 record 由全量 grep 对账抓出补齐——机械改名后必须 grep 对账

## 分层铁律闭合批：DO 出层清零 + 门禁类型化 + detail 首批生产者（2026-09-21，未提交）

- 规范化余量 ①④⑤ 一次做完：**①** 6 类 9 端点 DO 泄漏清零（VO 转换在 service，软删除字段不出 API）；**④** GateCheck/GateVerdict 类型化（落库 JSON 与历史逐键一致，单测逐字节锁定）；**⑤** detail 首批生产者——队列动作三态受理（不存在→A0005/404、状态不符→A0006/409 带 actualStatus 进 message+detail）与场景编辑富返回（未过条目随响应回编辑框）
- 前端：api.js 失败 Error 附 code/detail；main.js 错误按 A/B/C 族分话术；场景编辑未过时对话框保持打开逐条列原因
- 追加（用户点名）：16 文件内联全限定包路径清零（import + 简名；@MapperScan 字符串参数保留），残留 grep 清零、52/52 复验
- 验证：**52/52** 单测、vite build 过、真库冒烟（VO 形状 / cancel 三态 / detail.actualStatus 实战）；场景编辑富返回待真实编辑场景验证；**未 commit**
- 顺带核实：ch63 实为 09-20 深夜（透明化验收期）已 DIGESTED，旧记录"INTERRUPTED 可断点续跑"过时
- 运行态：Docker Desktop + pg-vector 已拉起，后端 8090 运行中（冒烟用）；前端 5173 未运行

## controller 收参 DTO 化（2026-09-21，未提交，分支 `09/0921-全流程透明化`）

- ②+③ 合并批：controller 裸 Map 收参清零（Library 14 + Planning 7 处换嵌套 record DTO，字段名与前端 payload 逐字对齐）；`RejectReq` 一名三义拆 `RejectDTO`/`SceneEditDTO(draftText)`/`FullTextEditDTO(fullText)`，前端 ChaptersView 两处 payload 同步改名；4 个必填点缺参从 NPE-500 收紧为 A0001-400
- 验证：编译过、全量 **49/49**、vite build 过；台账 `docs/design/代码卫生台账.md` §十；**未 commit**
- 规范化候选余量：①8 类 DO 出 service 层（违反分层铁律，量最大）④GateService check 行类型化 ⑤detail 通道生产者+api.js 接线

## Result 统一响应细化（2026-09-21，未提交，分支 `09/0921-全流程透明化`）

- 用户定调「返回类型分清楚，不是只有 ok 和 fail」后落第一批：`ok()`→`success()` 全量改名（7 controller 82 处）；fail 泛型化（失败值可出现在任意 `Result<T>` 返回位置）；新增独立 `detail` 字段承载失败结构化明细（BizException/异常处理器同步透传）；组件级 `@JsonInclude(NON_NULL)` 保成功响应 JSON 形状与旧版逐字节一致
- 明示不做：双泛型 `Result<T,E>`（编译期语义过不了 HTTP 边界，fail 只在异常处理器一处产出）
- 验证：新增 ResultTest 5 用例（含 JSON 形状基线锁定），全量 **49/49**；台账见 `docs/design/代码卫生台账.md` §九；**未 commit**
- 待续（期2/期3）：前端 api.js 接 code/detail + toast 按错误族分话术；detail 第一批生产者候选——A0006 状态冲突带实际状态、场景编辑带未过门禁条目

**运行时架构图见 `docs/architecture/`**（Archify 生成，自包含 HTML + 图源 JSON + 验收存档；v2 已纳 RAG 与复盘组件，`8e03a0c`）。

## 代码卫生战役收尾（2026-09-21，分支 `09/0921-全流程透明化`，`94d0337` 未推送）

- 用户定调「先把恶心代码搞干净」后分六期完成：**状态枚举化**（model/enums 八枚举，~90 处字面量替换，DO 保持 String 零 DB 风险）；**魔法值清零**（TuningDefaults 22 键/LlmTemps 15 温度/心跳与上限常量/前端 labels.js）；**Lombok 用满**（42 类 @RequiredArgsConstructor、21 处 @Slf4j、3 个含逻辑构造器迁 @PostConstruct、MiniMaxClient 装配抽 LlmClientConfig）；**桥接清场**（167 个 @Deprecated 访问器删除、全部调用点迁 getX()）；**降级显性化**（RAG 失败发事件、digest 提议跳过计数）
- 台账：`docs/design/代码卫生台账.md`（全 ✅ 含实录）
- 验证：每期编译+44/44 单测，三期后重启冒烟（DI/队列/trace/prompt），vite build，启动日志零 ERROR
- **明示不做（留给架构轮）**：多用户隔离、包重组/拆巨类（ChapterPipeline 800 行）、@EnumValue 持久化、微服务

## 全流程透明化四件套落地（2026-09-21 凌晨，未提交，分支 `09/0914-时序重构`，前置已 checkpoint `25b7028`）

- **前置 checkpoint**：契约施工①-⑦+追问三件套已固化提交（57 文件），本批为其后新增
- **LLM 流式通道**（探针先行：M3 思考=`<think>` 内联标签可跨帧、`stream_options.include_usage` 终帧精确 usage、无 [DONE]）：`chatStream()` 仅 scene_draft 接入，tuning `stream_long_text` 一键回退（V21）；CHUNK 事件 200ms 节流只推 SSE 不落库；**帧在飞点停 156ms 实测**（硬中断语义不回退）
- **工作台实时流式视图**：思考流灰色折叠+正文逐字+光标；CHUNK 不刷日志列表
- **章生成档案** `GET /api/chapters/{id}/trace` + 章节抽屉 [档案] tab：steps/calls（含单条成本+缓存）/checks（**全轮次全类型**含场景级与读者评审五问）时间线合并；调用条目展开**完整 prompt 分段/think/输出**（「AI 当时看到了什么」可回放，ch62 实测 user 段 9674 字上下文包全量）
- **台账露出补全**：llm-logs 详情 +promptMessages/cost/cachedTokens/createTime（DO 补字段）；LogsView 同步展示；**outline 归属修复**（此前 54 次调用 novelId/chapterId 双空）
- **会话视图**（agent-IDE 式）：工作台 RUNNING 行 [会话] 全屏转录（章分节/场景流式/判定原因展开/卷纲规划轮次 RETRY 事件+FAILED），自动滚动上翻暂停；SSE 监听扩至 16 类；队列行 kind 字段分叉显示
- 小项：审校 tab 读者评审五问全轮次、流水 pretty-print、待审批徽标点开明细
- 验证：44/44 单测（新增流式 10 个）、vite build、真实任务两轮点停实测、trace/台账真数据冒烟；**浏览器走查未做**（用户首开重点：生成输出流式/[会话]/[档案]）——详见 `docs/design/全流程透明化验收.md`

## 追问需求三件套落地：立刻停 / 实时阶段 / 人工编辑（2026-09-16 凌晨，未提交，分支 `09/0914-时序重构`）

- **硬中断 v2 实测 1.8 秒**：stop = cancel_requested 落库 + worker 线程 `Thread.interrupt()`（JDK HttpClient 响应中断，在飞调用毫秒级被弃）；实测任务 24 场景生成中点停 → 1.8s 任务 INTERRUPTED，章节/步骤行/事件三处如实记「用户终止（硬中断）」；工作台 [全部停止]（stop-all，二次确认）兜底失控
- **队列实时阶段列**：`GET /queue` 每行 `currentStep`（中文：章纲生成中/场景 N（检索/生成）/拼章+章级门禁/读者评审/AI 审校/等待审批/总结（digest）/卷纲规划中）+ `chapterTokens`（本章累计 token）；工作台新增 [当前阶段][本章tokens] 两列，3s 轮询
- **人工编辑双入口**：场景稿编辑 `PUT /api/scenes/{id}/edit`（生成前状态，保存即重过该场景机械门禁）+ 正文编辑 `PUT /api/chapters/{id}/fulltext`（仅待审批/已 digest；DIGESTED 保存 → digest 作废回待审批，重审即重算）；章节抽屉 [编辑]/[编辑正文] 按钮+对话框；**Q5b 全周期实测**（ch61 编辑→作废→还原→重审→digest 重算，数据已回原状）
- **PlanningView TDZ 崩溃修复**（用户截图 `Cannot access 'retro' before initialization`）
- 验证：34/34 单测、`vite build` 全量过、API 冒烟（stop-all=0/resume 守卫/编辑守卫 A0006×2）；实录与用户验收步骤见 `docs/design/契约施工验收.md` §三C/§三D/§四

## 契约施工 ①-⑦ 全批实施完毕（2026-09-15 深夜，未提交，分支 `09/0914-时序重构`）

- **全范围**：批①②③⑥（步骤状态行/停止/打回+章纲卡点/一屏答案）+ 追加批④⑤⑦（事后否决+digest 清除重算、插队暂停/继续、队列按书分道 `max_parallel_novels`、卷纲规划任务化 `auto-plan-async`、`retro_proposals` 复盘采纳+对话框按钮）。设计与按钮级规格见 `docs/design/契约施工设计.md`
- **验证**：编译过；34/34 单测过；V20 迁移应用（含一次 checksum 失配按 AGENTS 坑修复）；API 冒烟 11 项过；**真实端到端**：任务 22（ch61）经两次重启→分道认领→场景缓存续跑→DIGESTED 全程走通，`failureBrief` 顺带暴露并修复存量缺陷（proposeThreads 伏笔编码唯一键冲突炸 digest，已幂等跳过）
- **未完**：canon 裁决+提卡、保留双方版本、黑名单候选/style-metrics/golden set 验收数据、浏览器全链路走查——清单在 `docs/design/契约施工验收.md` §三/§三B/§五（② v2 硬中断已完成，见上节）
- **新调参键**：`reject_reason_max_len=200`、`max_parallel_novels=2`、`batch_max_chapters=10`（规划中）
- **注意**：ch61《系统章名甲》已由队列自动重生成并 DIGESTED（用户排队任务 22 续跑完成），此前 PENDING_APPROVAL 状态不再存在；ch63 因硬中断实测处 INTERRUPTED（无正文，可断点续跑）

## 修正后生成管线时序图 + UI 操作流图已落库（2026-09-15）

- **契约施工第一批已实施（2026-09-15 深夜，未提交）**：批①②③⑥——V20 迁移（reject_reason/INTERRUPTED/chapter_steps/tasks 扩展/调参键）、步骤状态行全埋点、失败结构化、流 0 硬停止（cancel 落库+场景边界终止）、流 A 打回+章纲卡点（manual 停 OUTLINED）、流 B 一屏读模型（详情四新字段+抽屉按钮横幅 tab+工作台停止/待审批）。编译过、34/34 单测过、API 冒烟 7 项过；浏览器全链路走查未做。**④⑤⑦ 未实施**，范围与回滚见 `docs/design/契约施工验收.md`
- **时序图** `docs/architecture/pipeline-sequence.html`（图源 `novelgen-pipeline.sequence.json`）：针对「该传不传/该记不记/假异步真串行」的目标调用链，四条契约——①章间交接物（过审即落交接行，章 N+1 只读交接行不等 digest）②每步有状态行（状态机只由记录推进，可删 healOrphanedApproved）③队列按 novel_id 分道（书内串行跨书并行，审批 digest 并入队列模型废除裸线程）④失败原因结构化（step/scene/round/原因一行，replan/队列/前端读同一份）
- **UI 操作流图** `docs/architecture/ui-workflow.html`（图源 `novelgen-ui.workflow.json`）：6 个用户任务×在哪屏→点什么→结果与缺口。核心发现：今天顺畅的任务只有提交生成一条；四个没有终点的任务=全新重生成/打回+意见/复盘建议采纳/digest 后台进度；「查失败原因」现状跨 3 页，目标态章详情一屏（=时序图契约②的 UI 消费方）
- 三图分工：组件图=有什么（现状），时序图=怎么传（目标），操作流图=怎么用（现状+缺口）；改造施工以后两者为准
- **设计对照审计** `docs/architecture/design-audit.md`：docs/design/ 三文档逐条 vs 代码——设计六处人工介入点只实现终审一处（章纲审批/canon 裁决/digest 人审/保留双方版本等均为欠账），与验收手段缺失（golden set/负样本）各列清单
- **契约文本源收敛到 `docs/architecture/pipeline-contracts.md`**（两张图的 mermaid 源+四条契约验收口径+人侧五流契约含流 0 硬停止+流 A 设计书）——改契约先改 md，HTML 图允许滞后
- 两图均 showcase 9/9、四视口 containment 全过；图源几何约束已记入该目录 README

## MP 迁移合入 main + 评审标准按书化 + 随章快照（2026-09-15，merge `6a310ff`，**未推送**）

- **合入**：`09/0913-MyBatisPlus迁移` 三提交合 main（157 文件 +5187/-2166）；`scripts/` 一次性迁移脚本已进 gitignore；STATUS/AGENTS 不入库
- **运行期回归三连修**（ch61 首跑实战暴露，单测未覆盖）：①XML resultType 不经 typeHandler——MaterialCardDO.aliases 读出 null 致场景上下文 NPE，TuningDO key/value 列名映射失位致缓存刷新 NPE，均改 resultMap 显式映射+空值防御；②ForeshadowMapper 迁移重复生成同名语句，去重
- **评审标准按书化（连贯性优先）**：读者评审改双阈值——结构性四问（hook/stakes/continuity/consequence）才是 blocker，fat_ratio 降为报告项、超硬上限才拦（代码侧降级留 raw_verdict）；reader_fix 剧情人设不得删改、字数可让路；恢复扩写线=预算下限×reader_fix_len_min（默认 0.75）
- **五参数三级覆盖**：gate_config 书级 > tuning 平台 > 代码默认（reader_fat_ratio_block/reader_fat_ratio_hard/reader_fix_len_min/reader_fix_len_max/ai_review_fix_floor）；调参入口三处——工作台「评审标准（本书）」面板、风格包门禁配置、素材库调参页；新接口 GET /api/novels/{id}/reader-standards、GET/PUT gate-config
- **随章快照**：V19 chapters.review_config，开章即写当时生效口径，章节抽屉展示「生成时评审：…」（历史章节如实标未记录）
- **ch61《系统章名甲》**：task#20 跑通（fat 0.38→0.35 两轮未达旧线 0.33 转人工）——**新标准下会直过**，现仍 PENDING_APPROVAL 待用户过审；ch62 **一次过审 DIGESTED 4169 字**（快照功能首次实战、F56/F63 随章落账、digest/世界状态全落库，MP 写路径四产出对账完毕）
- **《导入书A》书级标准已落库**：软 0.40 / 硬 0.55 / 恢复线 0.70（其余默认）——用户此前面板改值未点保存，已代落
- **main 领先远端 4 提交未推送**；`09/0913-新书孵化`（提取/孵化能力）暂停待续

## MyBatis-Plus + Lombok 迁移完成（2026-09-13，分支 `09/0913-MyBatisPlus迁移`，已合 main）

- **依赖**：mybatis-plus-spring-boot3-starter 3.5.12 + Lombok（SB 父版本管理）；application.yaml 增 `mybatis-plus.mapper-locations`；主类 `@MapperScan("com.zzdzz.novelgen.dao")`
- **架构**：20 个手搓 JdbcTemplate DAO 全部转为 MP 四层——`dao/XxxMapper`（BaseMapper+自定义方法）+ `resources/mapper/XxxMapper.xml`（全部原 SQL）+ `service.data/XxxDataService`（IService+自定义方法）+ `service.data.impl/XxxDataServiceImpl`；业务服务注入名统一 `xxxDATA`（如 `chapterData`），旧 DAO 类已删除
- **实体**：15 个 record DO 转 `@Data` 类（@TableName/@TableId，JsonNode 字段挂 JacksonTypeHandler+autoResultMap，MaterialCardDO.aliases 同）；补建 WorldState/VolumeReview/PipelineEvent/GenerationTask/Embedding 5 个实体；**保留 record 风格访问器桥接（@Deprecated）**——存量 `.field()` 调用零改动，后续逐步切 getX()
- **验证**：34 单测全过；后端启动 + 16 个读接口全 200 + MP 写路径（approval-mode/tuning update）持久化验证通过
- 已知小项：TuningDO 列名特殊（tkey/tvalue）已加 @TableField；`planning/events` 接口 400 为迁移前既有参数问题待查；桥接访问器为过渡设计，新代码请用 getX()

## 防注水与复盘注入已上线（2026-09-13，`4f97768`，已合 main `f74428e`）

- **FixA 防删过头/防注水回归**：reader_fix 提示词加入本章篇幅带（budgetMin–budgetMax×1.05）与「低于下限用情节对白补足、禁止补氛围」约束；修订稿跌破预算下限时自动启动一轮恢复扩写（只准恢复情节节拍，不恢复装饰段落，扩不动 best-effort 保留）；复用 reader_fix_len_min/max 护栏
- **FixB 复盘要点注入规划**：`packVolumePlan` 新增【上卷复盘要点】段（总评+最多 4 条 drifts+下卷建议，紧凑化 ≤1200 字，VolumeReviewDAO 读取上卷报告）；volume_plan 提示词新增规则 6（悬置伏笔优先安排兑现或给出理由、漂移须有修正安排）——代码与目录模板已同步
- 单测 34 个全过（新增复盘紧凑化 ×3）；重启验证 catalog_hash 自动对齐：volume_plan/user 与 reader_fix/user 均自动 v1→v2
- 应急调参 `reader_fix_len_min` 已在 ch59 实测后调至 0.75（见上节）

## 提示词注册表已上线·阶段二完成（2026-09-13，V18，库读取+可编辑）

- V18：prompt_templates 表（node/phase 唯一，exact/version/custom/catalog_hash/enabled + 软删三件套）
- **PromptCatalog**（llm/）：26 条模板集中目录——18 条 exact（格式模板，%s/%d 占位）已全部接入管线读取；8 条运行时拼接骨架仅浏览
- **管线从库读模板（fail-open）**：六个服务（Outline/ContextPacker/ChapterPipeline/Review/Digest/VolumePlan+VolumeReview）的模板调用改为 `promptTemplates.get/format`；30s 缓存；库内模板缺失/禁用/格式化失败一律回退代码模板，生成永不因坏模板中断
- **同步语义**：缺失才插入；代码模板变更时未定制行自动对齐（version+1，catalog_hash 比对）；custom 行永不覆盖
- **编辑闭环**：素材库「提示词」页签——列表（来源=代码/已改）+ 详情抽屉（编辑/保存/重置回代码版/复制）；PUT 保存时校验占位符序列与目录一致（A0001 拒绝坏模板）；POST /{id}/reset 一键重置
- 单测 31 个全过（新增 format fail-open ×4 + 库值优先/禁用回退 ×3）；PUT/校验拒绝/reset 全链路冒烟通过

## ch59 实测（2026-09-13，卷六首章，用户验收通过）

- 卷六 auto-plan 一次过审落库（59-68）；ch59 引用 F57（proposed→规划采纳→排期，自动闭环）
- 读者评审拦 consequence-fail（未接 ch58「若用到」钩子）+ 注水（三十四秒循环 ×10 等）→ reader_fix 删到 56%（3286→1855 字）→ 复审 pass → AI 审校 minor → 过审；**用户通读验收：读感合适**
- **发现缺口：reader_fix 后无字数重门禁**（可跌破预算下限无守卫）——已把 tuning `reader_fix_len_min` 调 0.75 应急；根治需代码：reader_fix 后补跑字数门禁
- **缺口二：复盘建议不进规划上下文**——卷六规划未采纳 F18（复盘 major 项），两个 Agent 之间缺「上卷复盘要点注入」通道
- 库读提示词路径全程零回退告警（阶段二真实生成验证通过）

## 卷五全自动闭环完成（2026-09-13，novel2 第 49-58 章，10/10 DIGESTED）

四新能力（戏剧四件套卷纲/事件后果注入/RAG/卷级复盘）首次全卷联合实战，全部生效：

- **卷纲规划**：auto-plan 两轮 BLOCKER 审校后落库（第一轮亡友/罗兰身份矛盾、F7 记账缺失被抓），10 章欲望/阻碍/转折/情绪落点齐全，伏笔 F11-F41 排期闭合
- **管线**：9/10 章零干预直达 DIGESTED；自愈梯子实战触发全部恢复（场景门禁重写×2、章级修订 tic 超标、reader_fix）
- **质量闸门首次拦人**：ch57 前两版被读者评审 blocker（开场静态+注水率 0.5→0.38 未过 0.33 线）转人工——正确行为；用「清场→replan 纠偏→重生成」链路第三滚过审。纠偏保留钩子三拍与 F38/F12/F40 引用，目标行加事件骨架（紫粉先于信使/夫人亲赴书房）
- **伏笔账**：排期核销 100% 按期（F36/37@49、F14@50、F11/15@52、F16@55、F21/22/41@58）；digest 新提议 F42-F57 共 16 条（proposed 待采纳）
- **卷五复盘**：机械对账 22 条全对（唯一 F18「planned 排期未兑现」被抓）；LLM 判 drift——2 major（F18 悬置、ch55 单章七节拍过载）+ 2 minor（修辞冗余、卷尾三连钩过密），下卷建议可执行
- **实战暴露的待修项**：①重新提交=断点续跑（复用 PASSED 场景），无「全新重生成」入口，需手工清库；②replan/regenerate 拒绝有正文之章，清场→纠偏→重生成应串成一个端点；③digest 的 markPlanted/markRecovered 按 `planted_in<=本章` 批扫，后章 digest 会提前翻转前章排期条目（幂等无害，语义不精确）
- 对话标记 29-59/章与卷四持平；单章成本仍约 20 万 tokens

## 卷级复盘 Agent 已上线（2026-09-13，分支 `09/0913-卷级复盘Agent` aa3f9a6，已合 main 01eb60c）

- V17：volume_reviews 表（每卷保留最新报告）
- **机械对账（确定性零 LLM）**：伏笔排期逐条核对账本（埋设/回收/逾期/账本缺编码）、字数预算 ±15% 容差带偏差、章节状态分布
- **LLM 漂移分析**（node=volume_review）：五类漂移（情节/伏笔/人物/世界观/节奏）+ 总评 + 亮点 + 下一卷建议；fail-open——LLM 挂了报告仍含机械对账
- PlanningView 卷纲每卷「卷级复盘」按钮 + 报告对话框；POST/GET /api/novels/{id}/planning/volumes/{vol}/review
- 实测卷四（39-48）：对账识别 F8-F15 埋设与 F4 跨卷回收；LLM 判 drift，下卷建议可执行（先回收卷首遗留伏笔再进倒计时主线、怀表兑现、紫边厚叶溯源）

## RAG 语义检索已上线（2026-09-13，分支 `09/0913-RAG检索注入` f4aa8f3，已合 main e60a9d3）

- V16：embeddings 表（pgvector 1536 维 HNSW）+ rag_enabled/rag_top_k/rag_max_distance 三个调参键
- **MiniMax 向量化协议实测**：embo-01 私有格式——`{"model","texts","type":"db|query"}` → `{"vectors":[...]}`，**实际 1536 维（官方资料标 1024，以实测为准）**；db/query 非对称检索不可混用；官方不返回 usage，tokens 按字符数近似记账
- **惰性索引**：打包场景时 `ensureNovelIndexed` 自动补嵌缺失项（幂等 upsert），digest/素材卡写入路径零改动；素材库素材卡页签有计数与手动回填按钮
- 场景上下文新增【相关前史（语义检索召回，带出处）】：查询=本章 goal+钩子+场景目标，近三章事实账不重复召回（前情摘要已覆盖）；开关关闭/无命中/调用失败一律降级不注入
- 实测：novel2 回填 50 条（27 事实账+23 卡）；导入书B ch5 全管线 3 场景 100% 注入，命中第1章事实账且近章过滤生效
- 上一章事件后果注入已合入 main（0d3b6b8）；事件后果简报与 RAG 段落同在场景上下文中

## 上一章事件后果注入已上线（2026-09-13，分支 `09/0913-事件后果注入` ffc8092，已合 main 0d3b6b8）

## 作品数据

- **《导入书A》（novel_id=2）**：**卷五（49-58）完卷，58 章全部 DIGESTED**（详见顶部卷五闭环一节）。卷四（39-48）44 章已过审；对话占比攻坚见卷四记录（密度 14-18/千字）。
- **伏笔台账**：F1-F16 全部回收；F21-F41 已埋设待回收；F18 planned 排期未兑现（卷五复盘 major 项，下卷 59-60 章需兑现）；F42-F57 proposed 待人工采纳。
- 《导入书B》（novel_id=1）：早期试验数据，ch3 FAILED，可清理。
- 事实账 19 条（22-40）、世界状态快照 11 份（30-40）、伏笔台账 F1-F7 已回收 + F8-F16 planned/planted。
- 成本参考：卷纲规划一次全闭环约 7-11 万 tokens / 5-8 分钟；生成单章约 20 万 tokens（chapter_revise 修订占大头——按节点模型路由的直接依据）。

## 管线重构已收尾（2026-09-12，分支 `09/0912-管线重构`，5 个提交）

五期全部落地，行为零变化，编译+单测+端到端回归全过：

- **期0** `c7418a9`：纯函数行为基线单测（GateService.computeMetrics/openingOverlap、LlmJson.read/repairStraightQuotes，24 个用例）。spring-boot-starter-test 本就在 pom。
- **期1** `0448731`：**LlmNode 常量类**（14 个节点名注册表，成本归集/路由键/路由页行清单同源）；Review/Digest/Outline 的 **三份 repairStraightQuotes 拷贝与手搓重试循环全删**，JSON 节点统一走 `LlmJson.ask/read`（fail-open 语义保留）；不另立 LlmGateway 转发层——LlmPort（文本）+LlmJson（JSON）即网关两面。
- **期2** `aacad5d`：**StageLog 事件门面**（Stage 15 个/Phase 18 个枚举，wire 值与前端 SSE 事件名逐字一致）；三个服务私有 emit 全删，事件名拼错从运行时静默变编译错误。
- **期3** `9731dd4`：**runChapter 从 250 行巨方法拆为编排器（~35 行）+5 个 step 方法 + Step 枚举**；读者评审与 AI 审校合并为共用 reviewStep 模板。原计划 PipelineStage 接口+DI Map 降级为枚举+step 方法（阶段序列固定无插拔需求，避免仪式代码；RAG 出现真插拔需求时 step 方法即策略类毛坯）。
- **期4** `32fe49d`：**Tuning 开关中心**（V15 表 16 个种子键 + TuningService 类型化访问/30s 缓存/fail-open 回退代码默认 + 素材库「调参」页签 GET /api/tuning、PUT /api/tuning/{key}）：修订轮数、长度护栏、场景长度带、比喻双闸（门禁 simile_per1k_abs_max + 提示词 prompt_simile_per1k）、读者注水率阈值、自愈梯子次数、卷纲审校轮数、命中卡上限。
- 回归证据：Spring 全上下文装配过（8 个服务构造器签名变更）、Flyway V15 干净迁移、44 章实测 ai_review 走新链路 verdict=pass、调参接口读写实测正常。
- 迁移已至 **V17**。

## 已上线能力（全部实测）

JWT 登录维持（重启不掉线）｜SSE 心跳｜异步生成队列（DB 承载/逐章进度/协作取消/重启重排）｜事件流水（pipeline_events 可回放）｜AI 语义审校闭环｜**卷纲规划 Agent**（auto 落库/manual 草稿，plan_mode 开关）｜**伏笔自动建账与采纳**｜**失败自愈梯子**（次数可调参）｜**素材卡**（pinned+别名命中注入）｜**LlmJson 公共件**（JSON 容错+喂回重试，全部 JSON 节点已收口）｜**StageLog 事件门面**（枚举锁事件名）｜**Tuning 调参中心**（素材库可改，30s 生效）｜按节点模型路由与峰谷计费（V12/V13）｜世界状态账｜时间跳跃字段贯通。

**待办（路由）**：从 MiniMax 控制台确认可用快模型型号与价格后，给 chapter_revise/scene_revise/ai_review_revise/world_state 四个机械节点填快模型；跑批尽量避开 14:00-18:00 高峰。

记忆四层：素材卡=设定层 → 事实账=事件层（滚动3条）→ 世界状态=时点层 → 伏笔台账=长线层（proposed→planned→planted→recovered）。

## 统一响应与错误码体系已按阿里手册改造（2026-09-12，`6dc3b4b`）

- **ErrorCode 枚举化**：字符串 5 位 = 来源字母 + 4 位编号（A 用户/B 系统/C 第三方/D 中间件/E 其他），成功码 "00000"，HTTP 语义挂在枚举上（与码值解耦），细分码建全（即使暂无使用方，手册格式合规）
- **外透异常语义修正**：11 处 service 层原生异常替换为 BizException——「章不存在」从 500 系统错误变 A0005+404、「不在待审批/已有正文」变 A0006+409、卷纲审校 3 轮未过变 C0003+502；dao/llm/runner 内部控制流异常保留
- **兜底净**：LlmException→C0002+502、参数类→A0001+400、其余→B0001+500；GlobalExceptionHandler 魔法数字清零
- **前端同步**：api.js 判 `code !== '00000'`（唯一契约改动点），401 跳登录不变
- 实测五个场景（错密码/未登录/不存在章/错状态/正常）全部符合预期。约 44 处旧 BizException 调用点因常量名兼容零改动。

## 审批流程与前端假死 bug 已修（2026-09-12，`b7f0038`）

- **历史性后端 bug**：approve() 在 digest 跑完后写 status='APPROVED'，库 CHECK 约束无此值，**每次人工审批必 500**（digest 成功、接口报错、状态不变）——PENDING_APPROVAL 转人工路径上线以来从没成功过。已改审批终点 DIGESTED（与 auto 流一致），并加原子防重（updateStatusIf，并发双击立刻 A0006）
- **前端假死修复**：ChaptersView 模板空值防御、审批按钮 loading（digest 需 1-2 分钟）、审批后刷新列表、main.js 全局 errorHandler 弹 toast 不再静默
- **embo-01 已入价目表**（0.5 元/百万 token，1024 维 embedding，高峰×2）——RAG 的前置条件齐了，只差实现

## 上一章事件后果注入已上线（2026-09-13，分支 `09/0913-事件后果注入` ffc8092，未合 main）

- `prevChapterBrief`：上一章「目标+章末钩子+实际收束」三行简报；收束优先 digest 摘要、无摘要回退原文末段（待审批章不再断链）
- 三处注入：章纲提示词（第一场景必须对接后果）、场景生成上下文（并参与素材卡别名匹配）、读者评审上下文
- 读者评审升为五问：新增 consequence（无视上一章未竟事件/钩子=blocker），修复指引同步
- 实测（导入书B ch4 全管线 ~35 分钟）：outline/scene 请求 100% 含后果段；读者评审 round1 blocker（fat 0.35）→重写→round2 pass（0.28），consequence 全程 pass
- **用户决策：现阶段全栈统一 MiniMax-M3**，快模型路由搁置（llm_node_config 路由行维持留空=全局默认）

## Backlog

### 系统侧
1. **卷四收尾完成**（44 章已过审）。下一步候选：卷级复盘 Agent（读全卷摘要+状态+伏笔账产出漂移报告）。
2. **digest 与审批解耦**（审批即暂记摘要，后续可重算）+ **上一章事件后果注入**（goal+hook+结尾梗概进下一章上下文，reader 评审加第五问）。
3. **RAG**（V16 pg-vector 嵌入表 over digests/素材卡，top-k 注入带出处；embedding 用 embo-01，价目已入库）；卷级复盘 Agent；digest 提卡；多用户隔离；小项（卷纲重规划伏笔回滚、39 章爪印左右不一致、44-48 goal 未用四件套格式）。
4. 卷五是「对话占比攻坚」+ 重构后管线的第一个完整检验卷（49+ 章目标用戏剧四件套）。

### 代码层欠账（2026-09-29 盘出，未排期）
1. 指纹 **fail-hard** 门禁（缺基线时是否放行需拍板）；chapter 状态终态收口 + 遗留 `FINAL` 迁移。
2. ChapterMapper 多处按 id 查询缺 is_deleted 守卫（软删行仍可取到）；散落硬编码常量与死代码清理。
3. 门禁指标**方向语义**须在指标定义处显式声明（上限/下限混用教训：dialogue_end_punct_ratio 场景级曾按上限执法）；修订轮成本优化（单轮 8 分钟）。
4. 测试缺口：门禁指标边界、断点复用分支未覆盖；LLM 429 限流未处理（402 余额耗尽已有先例）。

## 未提交改动

（未提交）`09/0928-文风修复与体验收尾`（领先 main 24 提交，未推送）+ 工作区 10 改：P0-1 EmbeddingMapper.xml、P0-2 ReviewService、复检 ChapterPipelineService、P1 GateService/ContextPackerService、MiniMaxClient、PromptCatalog、GenerationTaskDataServiceImpl、.gitignore、tools/style_baseline.py。待用户授权后提交。后端 8090（PID 96424）/前端 5173 运行中。

（空）`09/0922-走查与流式收尾` 六提交全推送远端（新分支首推）：a2eb4c6 修订流式/重连善后、e97a600 账本健康度、6f95105 伏笔自动园艺、9261611 失败自动重试(V22)、d82d3d4 审校透明化。待办：审校透明化与失败重试待下次生成实测；走查继续收发现。后端 8090/前端 5173 运行中。

（空）`09/0921-全流程透明化` 已 merge 入 main 并推送（`d1cd97c`，205 文件 +4238/-2893，含六连规范化与命名配对切换）；当前分支 `09/0922-走查与流式收尾`（自 main 新开，待办：浏览器走查修整、scene_revise/chapter_revise 接流式、会话断线重连 trace 打底）。后端 8090/前端 5173 运行中。
