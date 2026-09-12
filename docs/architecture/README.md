# 运行时架构图

由 [Archify](https://skills.sh/tt-a1i/archify/archify) 生成的自包含交互式架构图（2026-09-12，对应 main 分支 V15 时期）。

## 文件说明

| 文件 | 用途 |
|---|---|
| `runtime-architecture.html` | **成品图**：自包含交互式 HTML，双击浏览器打开。支持明暗主题、缩放/平移、搜索聚焦、关系追踪、导出 PNG/SVG/WebM |
| `novelgen-runtime.architecture.json` | 图源（唯一事实来源）：10 组件 / 3 边界 / 11 连线 / 4 说明卡片。改图改这个文件 |
| `runtime-architecture.visual-check.json` | 自动浏览器验收收据：四档视口（1440/1600/1920/2048）containment 与可读性全过 |
| `runtime-architecture.visual-check.html` | 验收对照页（含明暗两档截图） |
| `runtime-architecture.visual-check.1440x900.*.png` | 明暗主题截图存档 |

## 图的构成

- **主路径（绿色强调）**：浏览器 SPA → REST/SSE 接入层 → 生成队列 → 单章管线编排器 → 上下文打包器 → LLM 网关 → MiniMax；正文/状态/事件流水落 PostgreSQL，SSE 推回前端
- **信任边界**：A = 浏览器↔后端（JWT HttpOnly Cookie）；B = 后端↔MiniMax（API Key 仅存 application-local.yaml）
- **辅助信息**全部在四张说明卡片（主路径/记忆与闸门/信任边界/横切能力），不增加连线

## 改图再生成

```bash
cd ~/.agents/skills/archify
node bin/archify.mjs validate architecture <本目录>/novelgen-runtime.architecture.json --quality showcase --json
node bin/archify.mjs deliver architecture <本目录>/novelgen-runtime.architecture.json <本目录>/runtime-architecture.html --quality showcase --json
node bin/archify.mjs visual-check <本目录>/runtime-architecture.html --json
```

交付前须 showcase 校验 9/9、0 诊断，且 visual-check 四视口 containment 通过。
