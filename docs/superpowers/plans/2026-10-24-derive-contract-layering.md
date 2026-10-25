# DeriveFlow 合同分层重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 把当前“模型一次生成整套规划合同”的流程，重构为“系统生成稳定骨架 → 模型填充创作内容 → 状态账本校验 → 局部确定性修复”的分层工作流。

**Architecture:** 保留现有 run/step_status 与 derive 服务边界，但新增稳定的 scaffold、truth、knowledge 三类内部合同。结构事实（章号、拍 seq、线程 key、事实 key）由系统生成；LLM 只生成叙事内容和合同引用；最终校验基于统一状态账本，不靠多个阶段各自发明真相。

**Tech Stack:** Java 21、Spring Boot、Jackson、PostgreSQL、JUnit/Mockito。

**Spec:** 本计划依据 `docs/design/衍生故事规划与节拍路由设计.md`、`docs/STATUS.md` 中 run39–run43 实弹问题，以及当前 `DeriveStoryPlanService`、`DeriveBeatRouteService`、`DeriveDraftService`、`DeriveGateService` 实现。

## Global Constraints

- 不删除现有 run；新旧格式必须兼容。
- 不改用户显式输入的字数、题材、节奏、结局形态。
- 章号、拍 seq、线程 key、事实 key 不由模型自由发明。
- 模型输出错误只能触发局部重试或局部修复；禁止默认清空全书重规划。
- 所有确定性修复必须写 `llm_call_log.error_msg` 或 `derive_repair_log`。
- 不 commit/push，除非用户明确要求。

## Review Focus

- 用户只给字数与题材时，系统 scaffold 必须能生成可运行的章/拍骨架。
- 模型漏写合法拍、编造未知拍、放错章节时，必须能恢复或归一。
- 事实使用 `K-*` 稳定 key 后，知识账本必须能判断真实时间顺序。
- 真相卡、卷合同、章纲必须引用同一个 `TruthContract`，P6 不得再接受双轨真相。
- 历史 run 的自由文本知识与旧 `step_status` 仍可读。

### Task 1: 定义分层合同与稳定 ID

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/ContractLayer.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/contract/ContractLayerTest.java`

**Interfaces:**
- `enum ContractLayer { USER, SCAFFOLD, CREATIVE, STATE, REVIEW }`
- `record ScaffoldBeat(int tempSeq, int chapterNo, String beatType, String arcSlot, String sourceLabel, String factKey)`
- `record TruthContract(String truthId, String finalTruth, String finalChoice, List<String> forbidden, List<String> evidenceThreads)`
- `record KnowledgeFact(String factKey, String canonicalText, List<String> writerKeys, List<String> readerKeys)`

**Steps:**
- [ ] 写测试：`factKey` 对同一事实文本稳定、对不同文本不同。
- [ ] 实现稳定 key 生成；复用 `DeriveFactService.factKey` 的 SHA-256 口径。
- [ ] 增加合同层枚举与上述 records。
- [ ] 运行 `ContractLayerTest`。

### Task 2: 系统生成 Scaffold，不再让模型决定结构

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveScaffoldService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveScaffoldServiceTest.java`

**Interfaces:**
- `ScaffoldContract buildScaffold(RunDO run, int beatCount)`
- `ScaffoldContract` 含 `chapterCount`、`volumeRanges`、`List<ScaffoldBeat>`、`List<KnowledgeFact>`。
- `DeriveStoryPlanService.advanceScale` 只把 AI 输出当建议；系统按字数带、拍数、用户显式卷数生成最终 scaffold。

**Steps:**
- [ ] 写测试：用户未指定章数时，系统根据拍数与字数带生成章数；用户指定卷数时硬约束保留。
- [ ] 实现 scaffold 生成：章号连续、每章至少一个结构槽、拍 seq 全局唯一。
- [ ] 将 `PLAN_V2.scope` 改为引用 scaffold 版本，而不是直接把模型 `chapterCount` 当最终合同。
- [ ] 运行 `DeriveScaffoldServiceTest`。

### Task 3: 把 ROUTE 拆成“系统分配 + 模型填空”

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteServiceTest.java`

**Interfaces:**
- `RoutePlan enrichScaffold(ScaffoldContract scaffold, List<CharacterContract> characters, List<ThreadContract> threads)`
- LLM 输入只包含固定 `scaffoldBeatId`、角色 key、线程 key；模型输出只填 `beatRole`、`focusCharacters`、`threadRefs`、`writesKnowledge`、`requiresKnowledge`。

**Steps:**
- [ ] 写测试：模型漏写一个 scaffoldBeatId 时，系统保留该拍并用默认结构角色补齐。
- [ ] 写测试：模型输出未知 `scaffoldBeatId` 时丢弃并记录修复。
- [ ] 修改 ROUTE 提示词：模型不得输出 `seq`、`chapterNo`、章数。
- [ ] 实现按 scaffoldBeatId 回填章节与拍号。
- [ ] 运行 `DeriveBeatRouteServiceTest`。

### Task 4: 建立知识状态账本

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStateLedgerService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStateLedgerServiceTest.java`

**Interfaces:**
- `StateLedger simulate(List<BeatAssignment> orderedBeats, List<SyntheticAssignment> synthetic, List<CharacterContract> characters)`
- `StateLedger` 输出每个角色已知 `factKey` 集合、死亡/离场状态、线程状态。
- `validateAppearances` 改为消费 `StateLedger`，不再直接用自由文本匹配。

**Steps:**
- [ ] 写测试：同一 factKey 在前置拍写入后，后续拍可使用；未写入则报错。
- [ ] 写测试：后置写入不能让前置拍使用知识。
- [ ] 实现按 chapterNo/seq 排序的账本模拟。
- [ ] 将 `DeriveFactService` 的 canonical fact 与 `K-*` key 对齐。
- [ ] 运行 `DeriveStateLedgerServiceTest`。

### Task 5: 局部修复引擎替代全书回炉

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractRepairService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveContractRepairServiceTest.java`

**Interfaces:**
- `RepairResult repair(RoutePlan plan, StateLedger ledger, ScaffoldContract scaffold, PlanValidationVO issues)`
- `RepairResult` 只返回被修改的章、拍、事实、线程；不触发整卷删除。

**Steps:**
- [ ] 写测试：缺拍只补该拍；知识倒置只补前置事实拍；线程未兑现只补末章兑现拍。
- [ ] 实现修复优先级：结构缺拍 → 知识时序 → 线程兑现 → 章节内容冲突。
- [ ] 每次修复写 `derive_repair_log`，记录 before/after、原因、目标章拍。
- [ ] 运行 `DeriveContractRepairServiceTest`。

### Task 6: 统一 TruthContract，消除双轨真相

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveTruthContractService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveTruthContractServiceTest.java`

**Interfaces:**
- `TruthContract resolveTruth(RunDO run, JsonNode spine, JsonNode truthCard, JsonNode volumeContracts)`
- ⑥b 和 P6 只读同一个 `TruthContract`；发现真相卡与卷合同冲突时，先走局部 truth repair，再进入 P6。

**Steps:**
- [ ] 写测试：真相卡与卷合同给出两个 culprit 时，系统拒绝双轨并生成局部修复目标。
- [ ] 实现 truthId、finalTruth、finalChoice、forbidden、evidenceThreads 的统一落库。
- [ ] 修改 P6 prompt，要求只评价 `truthId` 对应内容。
- [ ] 运行 `DeriveTruthContractServiceTest`。

### Task 7: 兼容迁移与端到端验收

**Files:**
- Modify: `docs/STATUS.md`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowServiceTest.java`

**Steps:**
- [ ] 历史 run 无 `TruthContract` 时走旧读路径，不重算。
- [ ] 全量 `mvn -q test`。
- [ ] 打包并部署宝塔。
- [ ] 新建 run44，验证 ⑤→⑥a→⑥b→P6→⑦→⑧。
- [ ] 将 run44 的修复日志、P6 判定和最终状态写入 STATUS。
