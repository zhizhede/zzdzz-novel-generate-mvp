# MVP 共识与决策记录

> 2026-09-09/10 两轮设计拷问的定案。**与《系统架构设计》《技术选型与工程结构》冲突处，以本文为准。**
> 共识达成，M0 已开工。修改任何一条请直接编辑本文并注明日期。

## 一、平台定位（Q10/Q11 定案）

**让大量用户通过本平台进行不同小说的自动化编写**——多租户、多作品、风格无关。数据模型 day-1 按 `user_id/novel_id` 隔离，但 MVP 实际只有 admin 一个账号（仅 admin 登录，注册流程不实现，用户表 role + 注册开关配置预留）。

产品形态 = **一键全自动 + 混合审批**（Q10: a+c）：
- 工作节点可以人工审批，但**默认 `auto` 自动过审**；作品级开关可切 `manual`
- MVP **不做 AI 审批**（需先调研审批标准，后置）；MVP 的"生成" = 单章自动闭环（章纲→草稿→门禁→digest），"继续下一章"手动点；整卷连跑放 M4（带连跑上限配置）
- MVP 审批 = 章纲、章终稿两处人工卡点，manual 模式时门禁通过后停住等人

## 二、技术与工程决策

| # | 决策 | 依据 |
|---|---|---|
| 1 | 语言 Java 21 / Spring Boot 3.5.x / Maven；**MVP 不上 Spring AI**，自写 `LlmPort` + `MiniMaxClient`（RestClient + Jackson） | Q18=a；Spring AI 换 M4 adapter 接入（PgVectorStore/EmbeddingModel/tool calling 触发点） |
| 2 | 前端 Vue3 + Element Plus，前后端分离，单仓库 `web/` + `src/`（与 zzdzz-blog 同构） | Q13=a |
| 3 | PostgreSQL 16 第一天就用（本地复用 docker 容器 `pg-vector`，建库 `novel_gen`）；pgvector 到 M4 确认扩展 | Q3 演进定案 |
| 4 | 存储惯例（沿用 zzdzz-blog 实际代码）：`created_at/updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`；**所有表统一带 `deleted_at TIMESTAMPTZ` 软删除列**（含日志表，无例外）；软删唯一冲突用 partial unique index `WHERE deleted_at IS NULL` | 用户约束 |
| 5 | 包名 `com.zzdzz.novelgen`，artifact `novelgen-server` | 默认确认 |
| 6 | **新 JDK 一律装在 `D:\Program\Java\jdk-<版本号>` 目录**（不改动系统 JAVA_HOME） | 用户约束 |
| 7 | 部署 = 101.126.22.219（8核16G，宝塔面板，SSH key 见 zzdzz-blog/keys/）：宝塔 Java 项目跑 jar（端口 **8090**，避开 blog 8080）+ Nginx 反代新站点 + 同机 PG 新库；**生成进度走 SSE，nginx 照抄 blog 的 SSE 专用 location 配置**（gzip off / proxy_buffering off） | Q16 |
| 8 | MVP 全程免费（Q15=a），但 **`llm_call_log` 从 M0 内建**：每次 AI 调用记录 节点名/模型/prompt+completion tokens/耗时/成败/关联章节；章详情页显汇总 + 独立"调用记录"页 | Q15 |
| 9 | **生成结果整块展示，思考过程与正文分区**（2026-09-10）：推理模型的 think 内容不入正文、单独存 `llm_call_log.reasoning_text`；UI 上"AI 思考过程"折叠区 + 正文区分开展示；流式调用不做 usage 特殊处理 | 用户定案 |

## 三、范围裁剪（MVP 不做）

注册流程、计费/credits（流水表后置）、**合规全套（Q14=b：标识/审核/备案全后置，公开化前必须补导出链路 AI 标识与审核）**、AI 审批、用户自定义风格蒸馏、AI 评审（M2）、Spring AI/pgvector 语义召回（M4）、整卷自动连跑（M4）。

## 四、风格资产与验收

- 风格包 = 平台资产（Q12=c 分阶段）：MVP 预制"手搓风"包（蒸馏报告已就绪，语料/报告/范例均不入库），第二风格（如《咱家是猫》段流风）用于验证管线可复用；用户上传样本→自动蒸馏是 M3+ 旗舰功能
- 验收正典 = 续写《人类、法师、地下城》；《咱家是猫》24 章作门禁负样本测试集
- MVP 判定 = 机械门禁 + 风格指标数值回归（AI 评审 M2 才上，先攒"纯规则能拦多少"的对照数据）
- 章节字数 = 弹性制：场景数 × 场景预算，单章软上限 4500 字（回答了蒸馏报告"待确认③"）
- 断点重跑 = 场景级缓存，已过门禁场景不重新生成
- 首个切片 = 手写章纲 YAML 直接进"上下文打包→单场景生成→机械门禁"最短路径，先验证风格成立

## 五、里程碑（含 M0）

| 阶段 | 交付 | 验收 |
|---|---|---|
| **M0 骨架** | Boot 工程 + Flyway V1（全表 deleted_at）+ LlmPort/MiniMaxClient + llm_call_log + 冒烟（真实调 MiniMax 出手搓风续写段，展示 token 用量） | 冒烟输出 + 用量落库 |
| M1 单章直通 | admin Web 壳 + 作品工作台 + 手写章纲→生成→门禁→审批(auto/manual) | 一章全自动产出过门禁 |
| M2 评审闭环 | AI 评审 + 修订循环（≤3轮） | 首过率>50%，3轮内>90% |
| M3 记忆续写 | digest 链 + 角色状态推进 + 断点重跑 + 第二风格包 | 连续 3 章无事实矛盾 |
| M4 规模化 | Spring AI adapter + pgvector 召回 + 整卷连跑 + 服务器部署 | 架构文档 §九不变 |

## 六、调研结论备忘（2026-09-10）

- 学术管线（Re3/DOC/RecurrentGPT/DOME/AgentWrite/StoryWriter 等 10 系统）与五层架构高度吻合：分层规划+预算分段（9/10）、显式外部记忆（7/10）、评审-修订回环（6/10）。死路：单次直出长文、无规划滑窗续写、一次性静态大纲、未筛选 CoT
- 产品光谱：一键全自动（笔灵/蛙蛙，质量最不可控）↔ 引导式（Sudowrite Story Bible）↔ 分支续写（彩云小梦）。阅文/番茄刻意不做一键成书。所有产品靠"设定集实体卡注入"防跑偏
- 合规事实（Q14 选 b 后置，公开前补）：ICP 备案、AI 生成内容显式+隐式（元数据）标识（2025-09 施行）、内容审核+投诉入口为上线硬义务；调已备案模型走"登记"非完整备案，算法备案独立存在

## 七、硬约束（长期有效）

1. **未经用户明确允许，禁止任何 `git commit` / `git push`**
2. 数据库所有表带软删除列 `deleted_at`
3. 新 JDK 安装目录 = `D:\Program\Java\jdk-<版本号>`
4. 手稿原文、风格资产、API key 永不入库（`.gitignore` 已覆盖）
