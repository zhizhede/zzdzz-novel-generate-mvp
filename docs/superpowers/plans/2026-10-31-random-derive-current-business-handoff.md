# 随机衍生系统当前业务交接文档

日期：2026-10-31  
分支：`codex/remove-main-sample-derivative-chain-20261031`

> 这份文档只描述用户实际使用的随机衍生系统。  
> 不要把它和 SPLICE、文件工作区、旧样本拼接方案混为一谈。

## 1. 业务目标

系统要做的不是单纯“迁移剧情”，而是：

> 用户提供足够的数据源，系统自动处理素材、随机组合故事、规划小说、扩写正文、检查质量，最终交付一本可读的完整小说。

AI 的主要工作是：

```text
大纲/章节目标
→ 扩写成几千字正文
→ 系统检查
→ 人工最后打磨
```

系统要解决的重点不是让人从零手写每一章，而是：

- 稳定地产出初稿；
- 长篇不写崩；
- 人物、世界、时间、伏笔保持一致；
- 批量运行时成本可控；
- 人工只做最后判断和润色。

## 2. 数据源是什么

数据源不是系统直接读取的外部小说原文。

正确流程是：

```text
用户选择素材
→ 导入/登记到系统
→ 系统解析、抽取、无指向化、打标
→ 形成可复用素材池
→ 随机衍生 run 从素材池取样
```

数据源可以是：

- 用户导入的小说；
- 用户手工录入的剧情素材；
- 外部接口提供的素材；
- 其它可被系统处理的结构化素材。

当前 derive 入口包括：

```text
POST /api/derive/books
POST /api/derive/books/{id}/extract
POST /api/derive/books/{id}/deindex
POST /api/derive/books/{id}/grep
POST /api/derive/books/{id}/label
POST /api/derive/books/{id}/domains
POST /api/derive/books/{id}/style
```

处理后的核心素材是：

```text
asset_outline
asset_beat
asset_world
label_index
```

## 3. 当前随机衍生主链

当前实际主链是 run 管线，不是 SPLICE。

```text
POST /api/derive/runs
→ SAMPLING
→ PLANNING
→ DRAFTING
→ QC
→ PASSED / REWORK / FAILED
```

关键服务：

```text
DeriveSampleService
DerivePlanService
DeriveStoryPlanService
DeriveBeatRouteService
DeriveDraftService
DeriveWriteService
DeriveGateService
DeriveQcService
DeriveAutoFlowService
```

关键入口：

```text
POST /api/derive/runs
POST /api/derive/runs/{id}/auto
GET  /api/derive/runs/{id}/auto-progress
POST /api/derive/runs/{id}/auto/stop
POST /api/derive/runs/{id}/auto/kill
```

## 4. 当前系统已经具备的能力

- 数据源导入和素材处理；
- 无指向化；
- 打标、域格、文风；
- 随机约束采样；
- 合同、卷纲、章纲、拍路由；
- 章节生成；
- 机械门禁；
- AI 语义审校；
- digest；
- 自动续跑；
- run 状态和进度查询。

真实冒烟已经证明：

```text
run 可以创建
run 可以从 PLANNING 推进
流程可以进入 SCALE、SPINE、CHARACTERS
```

## 5. 当前系统还缺什么

### 5.1 数据源没有成为 run 的硬边界

当前：

```java
DeriveSampleService.create(RunCreateDTO)
```

从全局素材池读取：

```java
loadRows("OUTLINE", true)
loadRows("BEAT", true)
loadRows("WORLD", false)
```

还没有稳定支持：

```json
{"sourceIds":[10,11,12]}
```

因此目前不能可靠证明：

> 本次 run 只使用了用户指定的数据源。

### 5.2 SCALE/PLAN 还不够稳定

已有 run 曾在 SCALE 阶段因为：

- 章数范围；
- 平均章长；
- 结构约束；

连续失败。

这说明规模规划和结构合同还没有完全稳定。

### 5.3 100+ 章长篇能力尚未证明

当前还没有一次完整证据证明：

- 100 章能跑完；
- 100+ 章能分批生成；
- 中途重启能续跑；
- 单章失败不会拖垮全书；
- token、时间、重试成本可控。

10 章只能作为探针，不是生产上限。

### 5.4 人工终稿还不是正式交付状态

当前质量链能处理 AI 初稿和审校稿，但还需要明确：

- AI 初稿；
- AI 修订稿；
- 人工终稿；
- 最终交付版本；

之间的关系。

## 6. 当前不需要做的事

不要把随机衍生系统改成：

- SPLICE 系统；
- 文件优先系统；
- 独立正文旁路；
- 每个中间产物都必须落库的系统；
- 让模型负责所有结构字段的系统；
- 绕过既有门禁、审校、digest、审批的系统。

SPLICE 和文件工作区只能作为历史资料或业务验证工具，不能作为当前主链。

## 7. 推荐修复顺序

### 第一阶段：先让全流程稳定

先用现有全局素材池，修 SCALE/PLAN/DRAFTING 的稳定性，确保：

```text
建 run
→ PLANNING
→ DRAFTING
→ QC
```

能够稳定推进。

### 第二阶段：增加数据源选择

给建 run 增加：

```json
{"sourceIds":[10,11,12]}
```

然后：

- 按 `srcNovelId` 过滤无指向化素材；
- `run.source_ids` 落库；
- 抽中素材可追溯；
- 素材不足时明确失败；
- 不静默回退全局池。

### 第三阶段：验证长篇

用固定 seed 做：

- 10 章探针；
- 100 章分批验证；
- 100+ 章断点续跑验证；
- 预算和成本验证。

### 第四阶段：人工终稿

把人工修改纳入正式交付状态，明确：

- 哪一版是最终稿；
- 人工修改后是否重新过门禁；
- 如何保留 AI 初稿和审校稿。

## 8. 交接给下一个 AI 的硬规则

开始前必须读：

1. `最高约束.md`
2. `AGENTS.md`
3. `docs/architecture/pipeline-contracts.md`
4. `DeriveRunController.java`
5. `DeriveSampleService.java`
6. `DeriveAutoFlowService.java`
7. `DerivePlanService.java`
8. `DeriveDraftService.java`
9. `DeriveWriteService.java`
10. `DeriveGateService.java`
11. `DeriveQcService.java`

禁止：

- 先读旧 SPLICE 文档；
- 把 SPLICE 当当前主流程；
- 凭文档猜 Controller；
- 凭记忆猜数据库表；
- 在没有真实 run 证据时宣称业务通过。

## 9. 完成标准

只有同时满足以下条件，才能说随机衍生系统达到当前阶段目标：

- 数据源可以被用户选择；
- run 可以明确绑定 sourceIds；
- 取样只使用选定数据源；
- SCALE/PLAN 不再轻易失败；
- PLANNING/DRAFTING/QC 能稳定推进；
- 至少一次 10 章真实探针完成；
- 至少一次 100+ 章长篇分批方案验证；
- 有门禁、审校、digest、审批报告；
- token、耗时、重试有记录；
- 人工终稿状态明确；
- 不依赖 SPLICE 或文件工作区。
