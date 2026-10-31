# 随机衍生数据源选择：简明交接

## 当前问题

用户已处理数据源 `10/11/12`，但建 run 时系统仍从全局素材池取样。

当前代码：

```text
DeriveSampleService.create(RunCreateDTO)
```

读取：

```java
loadRows("OUTLINE", true)
loadRows("BEAT", true)
loadRows("WORLD", false)
```

没有根据用户选择的数据源过滤。


## 随机衍生系统现状

当前系统不是 SPLICE，也不是文件工作区系统。

它是一个基于 `run` 的随机衍生系统：

```text
数据源入库
→ 结构抽取
→ 无指向化
→ 打标/域格/文风
→ 建 run（约束采样）
→ PLANNING
→ DRAFTING
→ QC
→ PASSED / REWORK / FAILED
```

### 当前数据源处理

入口：

```text
POST /api/derive/books
POST /api/derive/books/{id}/extract
POST /api/derive/books/{id}/deindex
POST /api/derive/books/{id}/grep
POST /api/derive/books/{id}/label
POST /api/derive/books/{id}/domains
POST /api/derive/books/{id}/style
```

这些步骤形成可复用素材：

```text
asset_outline
asset_beat
asset_world
label_index
```

用户已经处理的数据源：

```text
10
11
12
```

### 当前建 run

入口：

```text
POST /api/derive/runs
```

实现：

```text
DeriveSampleService.create(RunCreateDTO)
```

当前代码读取：

```java
loadRows("OUTLINE", true)
loadRows("BEAT", true)
loadRows("WORLD", false)
```

它从全局素材池读取，没有根据用户选择的数据源过滤。

因此当前存在缺口：

```text
用户处理了数据源 10/11/12
→ 建 run 时仍可能从全局池抽样
→ 无法保证本次 run 只使用 10/11/12
```

### 当前随机衍生后续链路

run 创建后走既有主链：

```text
PLANNING
→ DRAFTING
→ QC
```

相关服务：

```text
DerivePlanService
DeriveStoryPlanService
DeriveDraftService
DeriveWriteService
DeriveGateService
DeriveQcService
DeriveAutoFlowService
```

这条链路必须保留，不新增旁路。

## 要改成什么样子

目标不是改掉随机衍生系统，而是补上数据源选择边界。

### 用户侧

用户处理完数据源后，可以在建 run 时选择：

```text
数据源 10
数据源 11
数据源 12
```

提交：

```json
{
  "sourceIds": [10, 11, 12],
  "tags": ["悬疑"],
  "pace": "0.75",
  "seed": 450066,
  "wordsMin": 12000,
  "wordsMax": 18000
}
```

### 系统侧

建 run 时：

1. 读取 `sourceIds`；
2. 只在这些数据源的无指向化素材中取样；
3. 将 `sourceIds` 写入 run；
4. 记录抽中的 outline/beat/world 来源；
5. 素材不足时明确失败；
6. 不静默扩大到全局池；
7. 不改变后续 PLANNING/DRAFTING/QC 主链。

### 数据侧

`run` 增加：

```text
source_ids JSONB
```

取样过滤：

```text
asset_outline.srcNovelId IN sourceIds
asset_beat.srcNovelId IN sourceIds
asset_world.srcNovelId IN sourceIds
```

### 验收侧

真实 run 必须证明：

- `run.source_ids=[10,11,12]`；
- 抽中素材全部来自 10/11/12；
- run 进入 PLANNING；
- 后续 plan/draft/write/gate/qc 正常；
- 不依赖 SPLICE；
- 不依赖文件工作区；
- 不绕过既有质量链。

## 不要改的东西

不要把这次修复扩大成：

- 重写随机衍生管线；
- 引入 SPLICE；
- 引入文件优先架构；
- 删除 DerivePlanService/DeriveDraftService/DeriveWriteService；
- 重写 GateService/QC；
- 重新设计整个 run 状态机。

本次只修复：

```text
用户选定数据源
→ 建 run
→ 取样范围
→ 来源落库
```

这一段。

## 要修什么

让建 run 支持：

```json
{"sourceIds":[10,11,12]}
```

并保证：

- 只从 10/11/12 的无指向化素材取样；
- `run.source_ids` 落库；
- 抽中资产的 `srcNovelId` 全部属于 sourceIds；
- 素材不足时明确报错；
- 不静默回退全局池；
- 不使用 SPLICE；
- 不使用文件工作区；
- 后续仍走既有随机衍生主链：

```text
SAMPLING
→ PLANNING
→ DRAFTING
→ QC
```

## 改动位置

1. `RunCreateDTO`
   - 增加 `List<Long> sourceIds`

2. `RunDO`
   - 增加 `String sourceIds`

3. derive 迁移目录
   - 新增 `V142__run_source_ids.sql`
   - `run.source_ids JSONB`

4. `DeriveRunMapper.xml`
   - `insertFull` 增加 `source_ids`
   - JSONB 显式 `::jsonb`

5. `DeriveSampleService`
   - `loadRows` 增加 sourceIds 参数；
   - `asset_outline/asset_beat/asset_world` 按 `srcNovelId IN sourceIds` 过滤；
   - 保存 run.sourceIds。

6. 前端建 run
   - 允许选择已完成处理的数据源；
   - 提交 `sourceIds`。

## 测试

必须覆盖：

```java
void createRunUsesOnlySelectedSources()
void createRunRejectsEmptySourceList()
void createRunFallsBackToGlobalPoolWhenSourceIdsNull()
void pickedAssetsAllBelongToSelectedSources()
void selectedSourceWithoutLabeledAssetsFailsExplicitly()
```

运行：

```powershell
mvn -q test -pl novelgen-service -am
```

## 真实探针

先 Read `DeriveRunController`，再用 Git Bash curl：

```bash
python -c "open('var/splice-probe/run.json','w',encoding='utf-8').write('{\"sourceIds\":[10,11,12],\"tags\":[\"悬疑\"],\"pace\":\"0.75\",\"seed\":450066,\"wordsMin\":12000,\"wordsMax\":18000}')"

curl -sS -b var/splice-probe/cookies.txt \
  -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs' \
  -H 'Content-Type: application/json' \
  -H 'Origin: https://zzdzz.cn:8888' \
  --data-binary @var/splice-probe/run.json
```

启动自动流程前，必须先确认 Controller 真实路径。

## 完成标准

- run.source_ids 正确；
- 只使用选定数据源；
- run 进入 PLANNING；
- 后续走既有 plan/draft/write/gate/qc；
- 真实探针完成；
- 不依赖 SPLICE 或文件工作区。

