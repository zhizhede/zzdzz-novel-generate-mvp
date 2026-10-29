# DeriveFlow run60：拍时序、揭示兑现与回炉范围闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans。按 U1→U9 顺序执行，每个 Task 先写失败测试再实现；不要跳步，不要在未获授权时 commit/push/部署。

**Goal:** 在上一批「合同 → 章级日程」已落地的基础上，堵住 run59 暴露的三个残余缺口——**⑥b 终局搬拍破坏拍章号、reveal 章没有线索兑现、揭示章缺 motive 文字**——让新 run60 的 P6 第 1 轮直接 `GO/PASS`，并且任何回炉都不再把局部问题放大成整本重写。

**Context（上一批已完成，勿重做）:** `2026-10-28-derive-single-context-schedule.md` 的 T1–T12 已落地：`V138`、`ChapterScheduleProjector`、`SCHEDULE` 单元、线程章号、日程派生章功能槽、机械注入 plant/payoff 拍、BATCH `clueEvents/closingActs`、HEAD C 去 truth、P6 日程预检、`resetForRepair`。线上已部署（jar `b1c56b5c…5739`，Flyway 138）。run59 在 P6 r1 得到 `REWORK/DRAFT`（5 项），已按用户指令强停。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16（`derive_gen`）、Jackson、JUnit 5/Mockito。**本批不加迁移**（tags 存 `truth_kernel` JSONB；无新表/新列）。

**Spec:** 本文件；实施前同时读 `docs/STATUS.md` 末两节、`docs/architecture/pipeline-contracts.md §五（流 S2）`、上一批计划 `2026-10-28-derive-single-context-schedule.md`。

---

## Global Constraints（违反即返工）

- 不改主站 `novelgen`；只改 `novelgen-derive` 链路；不改历史迁移；本批**不新增 Flyway 迁移**。
- 只有 CONTRACT 单元写故事事实；日程是纯函数投影；其余节点只读。
- **拍一旦由 SLOT_ROUTE 落库，章号不得被 ⑥b 的修复/归一改动**；只有显式 `replanVolume` 才能重排。
- 所有 LLM 结构化输出必须经 `LlmJson` + Java 二次校验；失败喂回 3 轮后 fail-fast，禁止静默降级。
- P6 不得改写合同/日程；`directive` 只能描述文字重写。
- 回炉最小化：`DRAFT` 只清“受影响章所在的窗”，不清全书；`PLAN_VOLUME:n` 只重排该卷；只有 `SCALE` 全书重建。
- **未经用户当轮明确允许，不 commit/push、不部署**；部署前记录旧 jar SHA256 并备份。
- 管线运行中禁止 `mvn clean`、禁止重启后端（AGENTS #5/#6）。

---

## 0. 执行前必读（无代码改动）

- [ ] Read `最高约束.md`（宪法闸；不读会被引擎取消 Edit/Write）。
- [ ] Read `AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md` 末两节、`docs/architecture/pipeline-contracts.md §五`。
- [ ] `git status --short`：工作区已有两批未提交改动，**禁止 reset/checkout/删除未跟踪文件**。
- [ ] 基线：`mvn -q test` 当前应全绿（上一批实测 service 657 / 全仓 751；STATUS 的 662 是笔误，不要照抄）。
- [ ] 确认 `derive_gen` schema=138；本批不改库结构。
- [ ] run59 是证据，**不要恢复**；新验收用 run60。

**验收:** 能复述三句话：拍章号落库后不可变；reveal 章必须有兑现；回炉只碰受影响窗。

---

## 1. “一次过”的验收口径（run60）

`POST /derive/api/derive/runs/{id}/auto?direct=false` 跑 run60（seed=450060，其余同 run59 fixture）：

- `derive_story_contract` 恰好 1 行 `FROZEN`，`version=1`，不增长；
- 合同 `truth_kernel` 含非空 `motiveTag/methodTag`，且**至少一条 clue 的 payoffChapter == revealChapter**；
- `derive_chapter_schedule` 的 reveal 行 `must_payoff` 非空；
- `beat_instance.chapter_no` 与 `derive_beat_pool.contract->>'chapterNo'` **逐拍一致（0 行 mismatch）**；
- 每条 clue 恰有 1 个 plant 拍 + 1 个 payoff 拍，章号=日程；
- `chapters.draft_outline` 的 reveal 章逐字包含 `culpritName` 与 `motiveTag`；
- **P6 第 1 轮 `GO/PASS`**，`derive_gate_review` 只有 1 行；
- 无 `CONTRACT_INVALID`、无 `HEAD_C_TRUTH_CONFLICT`、无 `BEAT_POOL_CHAPTER_MISMATCH`；
- 允许 `ADVISORY` note，不得有 `FIXABLE/HARD`。

> 若 r1 仍 `REWORK`：**立即 `POST /auto/kill`**，按 §7.4 收集证据并分类（结构类=实现 bug；纯内容类=提示词问题），禁止继续自动回炉、禁止放宽 P6、禁止 `direct=true`。

---

## 2. run59 证据与根因（executor 必须复核，但不需要重新推导）

### 2.1 run59 现场

- 合同：1 行 v1 FROZEN，culprit=裴野，reveal=12，cast=3，clues=4。
- 日程：15 行；plant 2/5/7/9，payoff 11/13/14/15，reveal 12 —— **reveal 章没有 clue payoff**。
- 拍：8 条线索拍章号正确、无 stray；`plan_contract.clueEvents` 正确。
- P6 r1：`REWORK/DRAFT`，5 项（02:02:17）：
  1. reveal 章缺 motive 兑现句；
  2. 被控者“解回”动作无章纲兑现；
  3. **拍22 被从第14章搬到第1章**，第1章写了第7/8/14章才有的证据；
  4. reveal 章只写 culprit+method，未写 motive；
  5. “被控者已被解回”无还原动作。
- 强停：02:03:40 `POST /auto/kill`，steps=20；run59 现为 `PLANNING`，15 章中 8 章有纲（1–8），9–15 空白。

### 2.2 根因（按严重度）

| # | 根因 | 代码位置 | 证据 |
|---|---|---|---|
| R1 | ⑥b 终局搬拍只按负载、保护角色，不保护章号/时序；`max=ceil(总拍*1.5/章数)` 与日程派生的 4–5 角色章冲突 | `DeriveDraftService.rebalanceBeatLoad`（L1964）、`isProtectedRole`（L2083）、`normalizeDraftContent`（L1886） | run59 总拍 20/15 章 → max=2；ch14 有 5 拍（payoff+highlight+choice+cost+closure），唯一非保护拍 seq22 被搬到最空的 ch1；`derive_beat_pool` 仍记 ch14 |
| R2 | `normalizeDraftContent` 的去重合并允许“相邻章”合并，跨章删拍 | `DeriveDraftService.normalizeDraftContent` | 代码判定 `adjacentChapter = same || abs(ch- ch)==1`，把右拍置 SKIPPED |
| R3 | 合同冻结规则不要求 reveal 章有 payoff；日程只要求 reveal 章有 `FINAL_REVEAL` | `StoryContractValidator.validate`、`ChapterScheduleProjector` | run59 reveal=12、payoffs 11/13/14/15 |
| R4 | BATCH 只校验 `closingActs` 声明与 culpritName，不校验 motive 文字 | `DeriveDraftService.validateScheduleEvents`（L1073） | ch12 closingActs=MOTIVE，但章纲无 motive 句；P6 才发现 |
| R5 | 回炉 >全书一半即回退全章 | `DeriveDraftService.resetForRepair`（L248-281） | run59 issues 抽到 8/15 章 → 清 15 章；kill 后 7 章空白 |
| R6 | 没有“拍池 vs 拍实例章号”一致性检查 | 无 | pool seq22=14，beat seq22=1，P6 前无人发现 |

---

## 3. 新增/强化的不变量（写进类注释）

1. **I1 拍章号不可变**：SLOT_ROUTE 落库后，`beat_instance.chapter_no` 不得被 ⑥b 的任何“修复/归一/负载均衡”改动；只有 `replanVolume` 可重排。
2. **I2 池拍一致**：`beat_instance.chapter_no == derive_beat_pool.contract.chapterNo`（按 `beat_key` 对齐）。
3. **I3 揭示必兑现**：合同 `revealChapter` 至少等于一条 clue 的 `payoffChapter`；reveal 章章纲必须写出 `culpritName` + `motiveTag`。
4. **I4 跨章不合并**：⑥b 去重/合并只能在**同一章**内做；跨章一律不删不并。
5. **I5 回炉按窗**：`DRAFT` 只清受影响章所在的窗；抽不到才全章。
6. **I6 冻结校验分层**：`validateForFreeze` 只用于新合同生成；`load` 继续用宽松的 `validate`，保证旧 run 可读。

---

## 4. 任务分解（按依赖顺序）

> **执行顺序：U1 → U3 → U2 → U4 → U5 → U6 → U7 → U8 → U9。** U2 的 `validateForFreeze` 引用 `Truth.motiveTag/methodTag`，必须先做 U3 的字段与解析；编号保留是为了和 run59 的归因表对应。

### U1 拍章号不可变：⑥b 搬拍/合并加硬闸（约 3h）

**Files:** `novelgen-service/.../service/DeriveDraftService.java`、`DeriveChapterPacker.java`、`DeriveBeatRouteService.java`；测试三个类。

**实现：**

1. `DeriveDraftService.normalizeDraftContent` 增加重载：
   ```java
   static DraftRepairResult normalizeDraftContent(List<ChapterDO> chapters,
                                                  List<BeatInstanceDO> beats)
   static DraftRepairResult normalizeDraftContent(List<ChapterDO> chapters,
                                                  List<BeatInstanceDO> beats,
                                                  List<ChapterScheduleEntry> schedule)
   ```
   两参版本委托三参并传 `List.of()`（旧测试/旧 run）。
2. 三参版本：
   - `boolean scheduleMode = schedule != null && !schedule.isEmpty();`
   - 去重循环里把 `adjacentChapter` 改成 `sameChapter`（`right.getChapterNo().equals(left.getChapterNo())`）**当 scheduleMode 时**；非 schedule 模式保持原行为；**任何模式下**，带 `threadRefs`/`writesKnowledge`/`requiresKnowledge`/`changesState`/`requiresState` 的拍都不参与合并（避免丢掉知识写入或状态变更拍）；
   - `scheduleMode` 时**跳过 `rebalanceBeatLoad`**；`normalizeDraftContent` 是静态方法拿不到 runId，由调用方（L537 之后）打一条
     `log.info("日程模式：跳过终局拍负载归一：runId={} schedule={}", runId, schedule.size())`，不要为日志改静态签名。
   - 调用于 L537 改为传 `scheduleService == null ? List.of() : scheduleService.load(run)`（该字段已存在于 DeriveDraftService L209）。
3. `rebalanceBeatLoad`（legacy 才走）增加 `canMove` 守卫：
   - `threadRefs` 非空、`writesKnowledge/requiresKnowledge` 非空、`dependsOn` 非空 → 不搬；
   - `targetChapter < sourceChapter` → 不搬（禁止倒搬）；
   - 其余保持原逻辑。
4. `DeriveChapterPacker.movableAssignment` 与 `DeriveBeatRouteService.rebalanceRouteLoad`：
   - 增加“带线索/知识/依赖的拍不搬”；
   - `movableAssignment` 增加 `target >= source` 判定（签名补 target 参数）；
   - 保持现有 clue 保护。
5. 测试：
   - `DeriveDraftBatchRepairTest`：15 章/20 拍 fixture（ch14 5 拍含 seq22）→ `normalizeDraftContent(chapters, beats, schedule)` 后 **seq22.chapterNo == 14**，且其它拍不跨章；`normalizeDraftContent(chapters, beats)`（无日程）仍允许旧行为（用不同 fixture 断言）。
   - `DeriveDraftBatchRepairTest.dedupeDoesNotMergeAcrossChaptersWhenSchedulePresent`：相邻两章相似 filled → 不合并。
   - `DeriveChapterPackerTest.doesNotMoveKnowledgeBeat`；`DeriveBeatRouteServiceTest.rebalanceKeepsKnowledgeAndThreads`。

**验收:** 定向测试绿；用 run59 的真实拍布局（可从 DB 导出 fixture JSON）跑 `normalizeDraftContent`，seq22 不再移动。

### U2 合同冻结：reveal 必须有 payoff + tags 必须有值（约 2h）

**Files:** `StoryContractValidator.java`、`DeriveContractService.java`、`StoryPlanningPrompts.java`、`PromptCatalog.java`。

**实现：**

1. `StoryContractValidator` 新增：
   ```java
   /** 只用于新合同生成（freeze 前）；load 仍用宽松 validate。 */
   public static List<String> validateForFreeze(StoryContract contract,
                                                PlanContracts.ScopeContract scope) {
       List<String> errors = new ArrayList<>(validate(contract, scope));
       if (contract.truth() != null && contract.truth().revealChapter() > 0) {
           boolean hasPayoff = contract.clues().stream()
                   .anyMatch(c -> c.payoffChapter() == contract.truth().revealChapter());
           if (!hasPayoff) errors.add("CONTRACT_REVEAL_NO_PAYOFF：revealChapter="
                   + contract.truth().revealChapter() + " 没有任何 clue 在该章兑现");
       }
       if (isBlank(contract.truth().motiveTag()) || contract.truth().motiveTag().trim().length() < 4) {
           errors.add("CONTRACT_TAG_MISSING：motiveTag 必须为非空核心短语（4~20 字）");
       }
       if (isBlank(contract.truth().methodTag()) || contract.truth().methodTag().trim().length() < 4) {
           errors.add("CONTRACT_TAG_MISSING：methodTag 必须为非空核心短语（4~20 字）");
       }
       return errors;
   }
   ```
2. `DeriveContractService.generateIfAbsent` 的解析 lambda 把 `StoryContractValidator.validate` 改为 `validateForFreeze`；`load` **不改**（旧合同无 tags/reveal payoff 仍可读）。
3. CONTRACT 提示词加规则 8/9（见 §5.1），JSON schema 的 truth 加 `motiveTag/methodTag`；同步 `PromptCatalog` 与 `ContractPromptArityTest`。
4. 测试：
   - `StoryContractValidatorTest.freezeRejectsRevealWithoutPayoff`；
   - `freezeRejectsBlankTags`；
   - `loadStillAcceptsLegacyContractWithoutTags`（用 7 参构造的旧合同，`validate` 通过）。
5. `DeriveGateService.scheduleIssues` 增加兜底：`schedule` 非空时，若 reveal 行的 `mustPayoff` 为空 → `CONTRACT_REVEAL_NO_PAYOFF`（合同级终态 `CONTRACT_INVALID`）；这样旧合同/旧日程即使被恢复也不会绕过。加测试 `gateRejectsScheduleRevealWithoutPayoff`。
6. 生成失败（3 轮仍违反 reveal-payoff/tags）→ **CONTRACT 单元 fail-fast**，不要回退到旧合同、不要放宽规则；日志写清具体错误码。

### U3 合同 Truth 增加 tags 字段（约 2h）

**Files:** `StoryContract.java`、`StoryContractParser.java`、`DeriveContractService.java`、测试。

**实现：**

1. `StoryContract.Truth` record 尾部加两字段，并保留 7 参构造器（避免 12 处旧调用全部改）：
   ```java
   public record Truth(String culpritKey, String culpritName, String motive, String method,
                       String finalTruth, String finalChoice, int revealChapter,
                       String motiveTag, String methodTag) {
       public Truth(String culpritKey, String culpritName, String motive, String method,
                    String finalTruth, String finalChoice, int revealChapter) {
           this(culpritKey, culpritName, motive, method, finalTruth, finalChoice,
                   revealChapter, "", "");
       }
   }
   ```
2. `StoryContractParser.parseTruth` 用 `text(node,"motiveTag","motive_tag")`、`text(node,"methodTag","method_tag")` 补 9 参。
3. `DeriveContractService.persistContract` 在 `truth` 节点写 `motiveTag/methodTag`；`rowToContract` 从 `truth_kernel` 读并补 9 参。
4. `promptContext` 已经是 `MAPPER.valueToTree(contract.truth())`，自动带 tags；确认 JSON 键为 `motiveTag/methodTag`。
5. `sameCanonical` **不比较 tags**（tags 是校验辅助，不参与“案情漂移”判定），避免旧合同 `""` vs 新值误报。
6. 测试：解析/落库/回读 tags；`DeriveContractServiceTest` 断言 v1 行含 tags。

### U4 ⑥b/P6 校验 reveal 章的 motive 文字（约 1.5h）

**Files:** `DeriveDraftService.validateScheduleEvents`、`DeriveGateService.revealNameIssues`。

**实现：**

1. `validateScheduleEvents` 的 reveal 分支加：
   ```java
   String motiveTag = contract.truth().motiveTag();
   if (!motiveTag.isBlank() && !outline.contains(motiveTag)) {
       drift.add("CONTRACT_CLOSURE_DRIFT：揭示章第" + no + "章 draft_outline 必须逐字写出 motiveTag「"
               + motiveTag + "」");
   }
   ```
2. `buildBatchUser` 的每章节点（窗口内）加 `motiveTag`/`culpritName`（只在 reveal 章或全部章都行，建议全部章给，便于铺垫）。
3. `DeriveGateService.revealNameIssues`：对 reveal 章额外检查 `motiveTag`（非空时）；沿用现有 culpritName 检查。
4. 测试：
   - `DeriveDraftContractTest.batchRevealOutlineMustContainMotiveTag`；
   - `DeriveGateContractTest.revealMotiveTagMissingFailsPreflight`（目标是 DRAFT，不是终态）。

### U5 拍池 vs 拍实例章号一致性（约 2h）

**Files:** `DerivePlanValidationService.java`、`DeriveGateService`（已有 planCheck 合流，无需大改）、测试。

**实现：**

1. `DerivePlanValidationService` 注入可选 `DeriveBeatPoolMapper`（`@Autowired(required=false)`）。
2. 新增 `beatPoolChapterIssues(long runId)`：
   - 读 `derive_beat_pool` 该 run 所有行；按 `beat_key` 建 map（`contract` JSON 里的 `chapterNo`）；
   - 读 `beat_instance`；对每个有 `beat_key` 的拍，若 pool 有同 key 且章号不同 → 生成 `PlanIssue("ERROR","BEAT_POOL_CHAPTER_MISMATCH", RepairScope.PLAN_VOLUME, volumeNo, poolChapter, List.of(seq), "拍 seq=X(beat_key) 实际第 A 章，路由合同第 B 章")`；
   - pool 缺失/beatKey 为空的行跳过（不误报）。
3. 在 `validateBookInternal` 末尾追加该方法结果；`buildResult` 会把 `RepairScope.PLAN_VOLUME` 映射成 `PLAN_VOLUME` target（现有 `toIssue` 逻辑）。
4. 测试：mock pool/beat，构造 seq22 pool=14、beat=1 → 断言 issue code/target；一致时无 issue。

### U6 回炉按窗清纲（约 2h）

**Files:** `DeriveDraftService.resetForRepair`、`DeriveDraftServiceTest`。

**实现：**

1. 把 L265-269 的“超过半本→全章”改为：
   ```java
   if (affected.isEmpty()) {
       affected = chapterNos;
       log.warn("DRAFT 回炉抽不到问题章，回退全章：runId={}", runId);
   } else {
       List<List<Integer>> wins = windowsByBeats(chapterNos, beats, WINDOW_CHAPTERS, WINDOW_BEATS);
       Set<Integer> affectedSet = new HashSet<>(affected);
       List<Integer> windowExpanded = wins.stream()
               .filter(w -> w.stream().anyMatch(affectedSet::contains))
               .flatMap(List::stream).sorted().toList();
       affected = windowExpanded;
       if (affected.size() == chapterNos.size()) {
           log.warn("DRAFT 回炉覆盖全部窗，等价全书重写：runId={}", runId);
       }
   }
   ```
   （`beats` 需要提前到 affected 计算之前加载；现在 L270 才加载，调整顺序。）
2. 日志改为“清章纲 N 章（窗 k/m，章节 a-b），未受影响章保留”，不要写“其余章保留”却清全书。
3. `dropDraftStateForChapters` 继续用 affected。
4. 测试：
   - 15 章/5 窗，issues 只点 ch11 → 只清窗 3（章 9–11），ch1–8 保留；
   - issues 点 8 章跨 4 窗 → 清 4 窗，窗 5 的章保留；
   - 抽不到 → 全章（fallback 保留）。

### U7 提示词/文档同步（约 1h）

- CONTRACT：§5.1；BATCH：§5.2；GATE：§5.3；`PromptCatalog` 与 `ContractPromptArityTest` 同步。
- `docs/architecture/pipeline-contracts.md §五` 追加本批三条不变量（拍章号不可变、reveal 必兑现、池拍一致）。
- `StoryPlanningPrompts.SPINE` 保持 `@Deprecated`，不动。

### U8 全量测试与构建（约 1h）

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test
mvn -q -pl novelgen-derive-app -am package -DskipTests
```

- [ ] 全绿；记录 jar SHA256；`git status` 只含本批预期文件。
- [ ] 不要照抄 STATUS 里旧的“662 条”；按实际输出记录（上一批实测 service 657 / 全仓 751）。

### U9 部署 + run60 验收（需用户当轮明确授权）

**Preconditions:** 用户当轮说“部署/上线”；线上无运行中管线；记录旧 jar SHA256。

```powershell
$K="keys\101.126.22.219_id_ed25519"; $H="root@101.126.22.219"
ssh -i $K $H "cp -a /www/wwwroot/novelgen-derive/novelgen-derive-app.jar /www/wwwroot/novelgen-derive/novelgen-derive-app.jar.bak-run60-20261028"
ssh -i $K $H "systemctl stop novelgen-derive"
scp -i $K novelgen-derive-app\target\novelgen-derive-app-0.1.0-SNAPSHOT.jar ${H}:/www/wwwroot/novelgen-derive/novelgen-derive-app.jar
ssh -i $K $H "chown www:www /www/wwwroot/novelgen-derive/novelgen-derive-app.jar; systemctl start novelgen-derive"
ssh -i $K $H "systemctl is-active novelgen-derive; ss -tlnp | grep 8091; grep -E 'Current version|Migrating' /www/wwwroot/novelgen-derive/logs/app.log | tail -3"
```

期望：active、8091、Flyway 仍是 138（本批无迁移）、无启动异常。

**创建 run60（同 run59 fixture，只换 seed）：**

```bash
curl -s -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs' \
  -H 'content-type: application/json' \
  -b 'novelgen_token=<当次登录 token>' \
  --data-raw '{"seed":450060,"wordsMin":12000,"wordsMax":18000,
    "tags":["悬疑","推理"],"pace":"0.75","form":"SHORT_STANDALONE","outcome":"闭合",
    "antiRepro":true,
    "intent":"现代都市单案短篇：一名调查者在一栋旧楼里调查一桩失踪案，只有三名角色，地点限于旧楼和走廊，结尾真相闭合，无死亡、超自然、第二案件。"}'
```

> 以上用 Git Bash 执行；不要粘进 PowerShell（引号/中文坑见 AGENTS）。
> **runId 说明**：接口返回的 `runId` 不一定是 60（序列可能跳号）；若返回值不同，把下文命令/SQL 里的 `60` 全部替换为实际 id，不要硬编码。

启动与轮询：

```bash
curl -s -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs/60/auto?direct=false' -b 'novelgen_token=…'
curl -s 'https://zzdzz.cn:8888/derive/api/derive/runs/60/auto-progress' -b 'novelgen_token=…'
```

**断言 SQL（分条 `-c` 执行）：**

```sql
-- 1) 合同只有一行 + tags + reveal
SELECT version,status,truth_kernel->>'culpritName' culprit,
       truth_kernel->>'revealChapter' reveal,
       truth_kernel->>'motiveTag' motive_tag,truth_kernel->>'methodTag' method_tag
FROM derive_story_contract WHERE run_id=60 ORDER BY version;

-- 2) reveal 行必须有 payoff
SELECT chapter_no,reveal,must_plant,must_payoff
FROM derive_chapter_schedule WHERE run_id=60 AND reveal ORDER BY chapter_no;

-- 3) 每拍章号 == 池合同章号（期望 0 行）
SELECT b.seq,b.beat_key,b.chapter_no AS beat_ch,(p.contract->>'chapterNo')::int AS pool_ch
FROM beat_instance b JOIN derive_beat_pool p
  ON p.run_id=b.run_id AND p.beat_key=b.beat_key
WHERE b.run_id=60 AND b.chapter_no <> (p.contract->>'chapterNo')::int
ORDER BY b.seq;

-- 4) 线索拍章号
SELECT chapter_no,seq,beat_role,route_contract->'threadRefs'
FROM beat_instance WHERE run_id=60 AND route_contract->'threadRefs' <> '[]'::jsonb
ORDER BY chapter_no,seq;

-- 5) reveal 章章纲含 culprit+motiveTag（章号从合同取，不写死）
SELECT c.chapter_no, c.draft_outline
FROM chapters c
JOIN run r ON r.id=60 AND c.novel_id=r.novel_id
JOIN derive_story_contract s ON s.run_id=r.id
WHERE c.chapter_no = (s.truth_kernel->>'revealChapter')::int;

-- 6) P6 轮次
SELECT round,verdict,action,repair_target,jsonb_array_length(issues) n
FROM derive_gate_review WHERE run_id=60 ORDER BY round;
```

**通过标准：** 第 6 条只有 round=1 且 `verdict=GO/PASS`；第 3 条 0 行；第 2 条 `must_payoff` 非空；合同 1 行且 tags 非空。
**停止规则：** r1 非 PASS → 立即 `POST /auto/kill`，收集 §7.4 证据，不要跑 r2、不要热修、不要 `direct=true`。

---

## 5. 提示词改动（逐字；改完跑 `ContractPromptArityTest`）

### 5.1 `derive_contract`（`StoryPlanningPrompts.CONTRACT` + `PromptCatalog` 同步）

在现有要求 7（自洽自检）后加：

```
8. revealChapter 必须至少等于一条 clue 的 payoffChapter：揭示章必须有一件线索当场兑现，
   否则结局三要素会悬空；
9. 额外输出两个短标签（4~20 字，禁止空、禁止只写“复仇/阴谋”这类空词）：
   motiveTag：motive 的核心短语（例：夺凭证控据点）；
   methodTag：method 的核心短语（例：符号引诱+催眠控心）；
   这两个标签会被下游章纲逐字引用并做确定性校验。
```

JSON schema 的 `truth` 加：

```json
"motiveTag":"…","methodTag":"…"
```

### 5.2 `BATCH`（`DeriveDraftService.BATCH_PROMPT`）

规则 9 改为：

```
9. reveal=true 的**这一章**必须用 closingActs 覆盖 CULPRIT/MOTIVE/METHOD 三项；
   draft_outline 必须逐字写出 culpritName 与 motiveTag（两者都在输入里）；
   其后各章只写收束/余韵；reveal 之前不得出现真凶姓名+手法结论。
```

`buildBatchUser` 每章节点加 `culpritName`/`motiveTag`（从合同取；没有则空串）。

### 5.3 `GATE`（`DeriveGateService.GATE_PROMPT`）

在“你的职责是诊断”后加：

```
揭示章的 motiveTag 与 culpritName 是合同事实，不得在 directive 中改写；
directive 只能描述“重写第 X 章的哪些句子”。
```

---

## 6. 测试矩阵

| 测试类 | 新增/修改用例 |
|---|---|
| `DeriveDraftBatchRepairTest` | `rebalanceSkippedWhenSchedulePresent`、`dedupeDoesNotMergeAcrossChaptersWhenSchedulePresent` |
| `DeriveChapterPackerTest` | `doesNotMoveKnowledgeBeat`、`doesNotMoveThreadBeat` |
| `DeriveBeatRouteServiceTest` | `rebalanceKeepsKnowledgeAndThreads` |
| `StoryContractValidatorTest` | `freezeRejectsRevealWithoutPayoff`、`freezeRejectsBlankTags`、`legacyValidateStillPasses` |
| `DeriveContractServiceTest` | `generatedContractCarriesTags`、`freezeRetryOnRevealNoPayoff` |
| `DeriveDraftContractTest` | `batchRevealOutlineMustContainMotiveTag` |
| `DeriveGateContractTest` | `revealMotiveTagMissingFailsPreflight`、`beatPoolChapterMismatchBlocksWithPlanVolume` |
| `DeriveResetServiceTest` | `draftRepairClearsOnlyAffectedWindows`、`noHalfBookFallback` |
| `DerivePlanValidationServiceTest` | `beatPoolChapterMismatchReported` |

定向命令：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am `
  "-Dtest=DeriveDraftBatchRepairTest,DeriveChapterPackerTest,DeriveBeatRouteServiceTest,StoryContractValidatorTest,DeriveContractServiceTest,DeriveDraftContractTest,DeriveGateContractTest,DeriveResetServiceTest,DerivePlanValidationServiceTest,ContractPromptArityTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

---

## 7. 部署/验收细节

### 7.1 部署

见 U9；本批无 V139，Flyway 保持 138。旧 jar 备份命名 `bak-run60-20261028`。

### 7.2 建 run

run60 seed=450060，其余参数同 run59（12000–18000 字、悬疑/推理、pace=0.75、SHORT_STANDALONE、闭合、antiRepro=true、同一 intent）。

### 7.3 阶段预期

`SCALE → CONTRACT → SCHEDULE → SPINE → VOLUMES → CHARACTERS → SLOT_ROUTE → ⑥b → P6 r1`，`completedUnits` 含 SCHEDULE。

### 7.4 失败时收集（首个分歧点）

1. `run.step_status` 的 `PLAN_V2/GATE`；
2. `derive_story_contract`（版本、tags、reveal）；
3. `derive_chapter_schedule`（reveal 行 payoff）；
4. `beat_instance` vs `derive_beat_pool` 章号（第 3 条 SQL）；
5. `chapters.draft_outline` 的 reveal 章；
6. `derive_gate_review` 全部行 + `llm_call_log` 该 run 的 error。

分类：合同/日程/拍池/章号类 = 实现 bug（回 U1–U5）；纯内容类（重复、节奏、动机铺垫）= 提示词/模型问题（下一批，不属本批 DoD）。

---

## 8. 回滚

- 恢复 `/www/wwwroot/novelgen-derive/novelgen-derive-app.jar.bak-run60-20261028`，`systemctl restart novelgen-derive`。
- 本批无迁移，库无需回滚；run60 独立，不影响历史 run。

---

## 9. 风险与不保证

1. **P6 是 LLM**：本批能让“拍章号/池一致/reveal 兑现/motive 标签/回炉范围”变成确定性，但文笔、节奏、动机铺垫仍由 LLM 评审，不能 100% 保证一次过；若 r1 只剩内容类，按 §7.4 归因，不属本批代码缺陷。
2. **tags 的副作用**：motiveTag 可能写得生硬；prompt 要求“核心短语、可原样入章纲”，若模型写得过长，BATCH 会重试 3 轮后失败——这比 P6 才发现好，但仍需观察。
3. **旧 run**：`load` 保持宽松 `validate`，旧合同无 tags 也能读；但 `resetForRepair` 的按窗逻辑对旧 run 同样生效，旧 run 恢复前先确认。
4. **多卷**：本批不改变多卷逻辑；多卷的 reveal/payoff 规则同样适用，但线上仍只验收单卷。
5. **不要做**：不要停用 P6、不要 `direct=true`、不要把 `BEAT_POOL_CHAPTER_MISMATCH` 降成 WARN、不要把 `CONTRACT_REVEAL_NO_PAYOFF` 改成 `REWORK`。

---

## 10. Definition of Done

- [ ] `normalizeDraftContent` 在日程模式下不搬拍、不跨章合并；legacy 行为有测试钉住。
- [ ] `rebalanceBeatLoad`/packer/route 归一不搬带线索/知识/依赖的拍，且不倒搬。
- [ ] `validateForFreeze` 拒绝“reveal 无 payoff / tags 空”；`load` 仍接受旧合同。
- [ ] `Truth` 带 tags，解析/持久化/回读/提示词全链路一致。
- [ ] BATCH 与 P6 预检都校验 reveal 章 `culpritName` + `motiveTag`。
- [ ] `derive_beat_pool` 与 `beat_instance` 章号一致检查上线（0 mismatch 才算过）。
- [ ] `resetForRepair` 只清受影响窗，日志与行为一致。
- [ ] 全量 `mvn -q test` 绿；derive jar 构建通过；jar SHA256 记录。
- [ ] （授权后）部署 active/8091/Flyway 138；run60 P6 r1 GO/PASS。
- [ ] `docs/STATUS.md` 追加本批记录（未授权不 commit）。

---

## 附录 A：run59 证据（只读）

- run59：seed 450059，novel 122，15 章，0 正文；合同 v1 FROZEN，裴野，reveal 12，clues 4。
- 日程：plant 2/5/7/9，payoff 11/13/14/15，reveal 12（无 payoff）。
- P6 r1 5 项：motive 缺、解回缺、seq22 搬 ch14→ch1、reveal 只写 culprit+method、解回无动作。
- 搬拍日志：`终局拍负载归一：seq=22 从第14章移至第1章`；`derive_beat_pool` seq22 仍 ch14。
- 强停：02:03:40，steps=20；章纲 8/15（空白 9–15）。

## 附录 B：关键代码索引（行号会漂，用方法名搜索）

- 拍搬移：`DeriveDraftService.rebalanceBeatLoad`、`isProtectedRole`、`normalizeDraftContent`；`DeriveChapterPacker.movableAssignment`；`DeriveBeatRouteService.rebalanceRouteLoad`
- 回炉：`DeriveDraftService.resetForRepair`、`repairChapters`、`affectedWindows`
- 合同：`StoryContractValidator.validate/validateForFreeze`；`DeriveContractService.generateIfAbsent/persistContract/rowToContract`
- ⑥b：`validateScheduleEvents`、`BATCH_PROMPT`、`buildBatchUser`
- 闸：`DeriveGateService.revealNameIssues`、`scheduleIssues`、`decideGate`
- 校验：`DerivePlanValidationService.validateBook/validateBookInternal`
- 池：`DeriveBeatPoolMapper`、`derive_beat_pool.contract.chapterNo`

## 附录 C：常用命令

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test
mvn -q -pl novelgen-derive-app -am package -DskipTests
```

```bash
# 线上查库（先 \d 再 SQL）
ssh -i keys/101.126.22.219_id_ed25519 root@101.126.22.219 \
  "docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -d derive_gen -c '\d derive_beat_pool'"
```

```bash
# 强停（r1 非 PASS 时立即执行）
curl -s -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs/60/auto/kill' -b 'novelgen_token=…'
```
