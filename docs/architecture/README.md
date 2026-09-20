# 运行时架构图

由 [Archify](https://skills.sh/tt-a1i/archify/archify) 生成的自包含交互式图表（2026-09-12，对应 main 分支 V15 时期）。

现有三张：**组件图**（runtime-architecture，现状拓扑）、**修正后时序图**（pipeline-sequence，2026-09-15，目标时序契约）、**UI 操作流图**（ui-workflow，2026-09-15，六个用户任务×页面×缺口）。三图各管一层：组件=有什么，时序=怎么传，操作流=怎么用；改造施工以时序图+操作流图为准。

> **契约文本源：[`pipeline-contracts.md`](pipeline-contracts.md)**——两张契约图的 mermaid 源+四条契约验收口径+缺口清单都在这一个 markdown 里。改契约先改 md，HTML 图是展示生成物，允许滞后。

## 文件说明

| 文件 | 用途 |
|---|---|
| `runtime-architecture.html` | **成品图**：自包含交互式 HTML，双击浏览器打开。支持明暗主题、缩放/平移、搜索聚焦、关系追踪、导出 PNG/SVG/WebM |
| `novelgen-runtime.architecture.json` | 图源（唯一事实来源）：10 组件 / 3 边界 / 11 连线 / 4 说明卡片。改图改这个文件 |
| `runtime-architecture.visual-check.json` | 自动浏览器验收收据：四档视口（1440/1600/1920/2048）containment 与可读性全过 |
| `runtime-architecture.visual-check.html` | 验收对照页（含明暗两档截图） |
| `runtime-architecture.visual-check.1440x900.*.png` | 明暗主题截图存档 |
| `pipeline-sequence.html` | **修正后时序图**：章间交接物·状态行·按书分道三契约的目标调用链，交互能力同上 |
| `novelgen-pipeline.sequence.json` | 时序图图源（唯一事实来源）：7 参与者 / 4 段带 / 11 消息 / 3 契约卡片 |
| `pipeline-sequence.visual-check.json` | 时序图验收收据：四档视口 containment 与可读性全过 |
| `pipeline-sequence.visual-check.html` | 时序图验收对照页 |
| `pipeline-sequence.visual-check.*.png` | 时序图明暗主题截图存档 |
| `ui-workflow.html` | **UI 操作流图**：6 个用户任务（提交/盯进度/审批/查失败/纠偏重跑/复盘纠偏）× 在哪屏→点什么→结果与缺口，红色标缺口、虚线标目标态 |
| `novelgen-ui.workflow.json` | 操作流图源（唯一事实来源）：3 泳道 × 6 任务组 × 18 节点 / 12 边 / 3 卡片 |
| `ui-workflow.visual-check.json` | 操作流图验收收据：四档视口 containment 与可读性全过 |
| `ui-workflow.visual-check.*.png/.html` | 操作流图截图与对照页存档 |

## 组件图的构成

- **主路径（绿色强调）**：浏览器 SPA → REST/SSE 接入层 → 生成队列 → 单章管线编排器 → 上下文打包器 → LLM 网关 → MiniMax；正文/状态/事件流水落 PostgreSQL，SSE 推回前端
- **信任边界**：A = 浏览器↔后端（JWT HttpOnly Cookie）；B = 后端↔MiniMax（API Key 仅存 application-local.yaml）
- **辅助信息**全部在四张说明卡片（主路径/记忆与闸门/信任边界/横切能力），不增加连线

## 修正后时序图的四条契约

1. **章间交接物**：章 N 过审即落交接行（结尾钩子/未竟事件/新伏笔提议）；章 N+1 章纲只读交接行，不等 digest——digest 降级为后台质量工序，落后不阻塞生成主链
2. **每步有状态**：章纲/场景/评审/digest 各有状态行，章节状态机只由记录推进；断点续跑=读显式执行位置；启动补偿扫描（healOrphanedApproved）随之删除
3. **按书分道**：生成队列按 novel_id 分 worker，书内串行保因果、跨书并行互不阻塞；人工审批的 digest 并入同一队列模型，废除裸单线程 executor
4. **失败原因结构化**（并入契约二卡片）：未过/异常落结构化行（step/scene/round/原因原文），replan、队列消息、前端详情读同一份

## UI 操作流图的核心发现

- **今天顺畅的用户任务只有一条**（提交生成）；盯进度粒度粗、审批干等、查失败跨 3 页、纠偏重跑手工三步、复盘只读无采纳
- **四个没有终点的任务**：全新重生成（断点续跑冒充）、打回+意见、复盘建议采纳/拒绝、digest 后台进度——操作流上直接暴露
- 目标态（虚线节点）与修正后时序图契约一一对应：章详情一屏=契约②状态行的 UI 消费方

## 改图再生成

```bash
cd ~/.agents/skills/archify
# 组件图
node bin/archify.mjs validate architecture <本目录>/novelgen-runtime.architecture.json --quality showcase --json
node bin/archify.mjs deliver architecture <本目录>/novelgen-runtime.architecture.json <本目录>/runtime-architecture.html --quality showcase --json
node bin/archify.mjs visual-check <本目录>/runtime-architecture.html --json
# 时序图
node bin/archify.mjs validate sequence <本目录>/novelgen-pipeline.sequence.json --quality showcase --json
node bin/archify.mjs deliver sequence <本目录>/novelgen-pipeline.sequence.json <本目录>/pipeline-sequence.html --quality showcase --json
node bin/archify.mjs visual-check <本目录>/pipeline-sequence.html --json
# UI 操作流图
node bin/archify.mjs validate workflow <本目录>/novelgen-ui.workflow.json --quality showcase --json
node bin/archify.mjs deliver workflow <本目录>/novelgen-ui.workflow.json <本目录>/ui-workflow.html --quality showcase --json
node bin/archify.mjs visual-check <本目录>/ui-workflow.html --json
```

交付前须 showcase 校验 9/9、0 诊断，且 visual-check 四视口 containment 通过。

时序图图源注意：viewBox 宽受投影字号下限约束（1440 视口可用宽约 930 时 sublabel 7px 需 ≥6px 投影，宽 ≤~1050），高受首屏 containment 约束（H=560 时消息 y 上限≈477）；改布局后以 validate 诊断为准确认。

操作流图图源注意（workflow v2）：泳道数决定最小高度（6 泳道 ≥912 必爆首屏，3 泳道 ~560 可过）；宽受投影字号约束（sublabel 8px，1440 视口可用宽 930 → W ≤~1240，实测 1150 过）；groups.variant 只收 default/emphasis/security/dashed（exception 是 lanes 专用）。
