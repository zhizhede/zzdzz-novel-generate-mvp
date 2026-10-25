# DeriveFlow 拍合同与知识账本修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让模型漏写合法拍、编造未知拍、以及把知识要求放到知识写入之前时，系统能确定性归一并继续推进，而不是停止整卷。

**Architecture:** 在 `DeriveBeatRouteService` 内增加拍合同恢复与知识账本修复：先按输入拍库存恢复缺失合法 seq，再按章节/seq 顺序模拟角色知识；对“后拍写入、前拍要求”的倒置，在更早章节注入带 `writesKnowledge` 的 synthetic prerequisite。保留现有硬校验作为最终防线。

**Tech Stack:** Java 21、Spring Boot、Jackson、JUnit/Mockito。

**Spec:** `docs/superpowers/plans/2026-10-24-derive-contract-repair.md`

## Global Constraints

- 不改数据库表结构；不新增外部依赖。
- 保留“每章至少 1 拍”和线程/角色引用合法性。
- 未知 seq 继续丢弃；合法 seq 不得静默丢失。
- 所有确定性修复写日志；最终校验失败仍抛 `PlanJson.Bad`。
- 不 commit/push，除非用户明确要求。

## Review Focus

- 合法拍漏写：`seq=17` 缺失时必须恢复到合理章节，且最终覆盖校验通过。
- 知识倒置：后拍 `writesKnowledge`、前拍 `requiresKnowledge` 时必须生成前置 synthetic 拍或调整焦点角色。
- synthetic 拍不能引用未知角色/线程。
- 现有未知 seq、强制跳拍、章槽归一回归不得退化。
- 全量测试通过。

### Task 1: 恢复合法漏写拍

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteServiceTest.java`

**Interfaces:**
- `static PlanContracts.RoutePlan recoverMissingAssignments(PlanContracts.RoutePlan plan, RouteContext ctx)`
- 返回补齐 assignments 的新 `RoutePlan`。

**Steps:**
- [ ] 写测试：输入 inventory 含 seq 1/2/3，模型只分配 1/3，期望 seq2 被恢复且覆盖校验通过。
- [ ] 实现按最近已分配 seq 的章节恢复缺失拍；beatRole 由 beatType 映射，默认 `MAIN_ADVANCE`。
- [ ] 运行 `DeriveBeatRouteServiceTest`。

### Task 2: 知识账本与倒置修复

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteServiceTest.java`

**Interfaces:**
- 扩展 `repairKnowledgeCausality(RoutePlan plan)`，必要时返回带 synthetic prerequisite 的新 plan。

**Steps:**
- [ ] 写测试：seq39/ch15 要求事实 F，seq16/ch16 写入 F；期望生成更早章节的 synthetic 拍，校验通过。
- [ ] 实现按章节/seq 排序的知识账本；后置写入导致前置缺口时，注入 `writesKnowledge=F` 的 synthetic 拍。
- [ ] 运行 `DeriveBeatRouteServiceTest`。

### Task 3: 回归与部署

**Files:**
- Modify: `docs/STATUS.md`

**Steps:**
- [ ] 运行 `mvn -q test`。
- [ ] 打包 `mvn -q -pl novelgen-derive-app -am package -DskipTests`。
- [ ] 部署到宝塔并创建 run43，观察自动流程。