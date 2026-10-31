# 随机衍生系统：数据源选择与真实探针修复方案

> 本方案只针对用户实际使用的随机衍生 run 管线，不针对 SPLICE。  
> SPLICE 相关文档、端点、部署和探针全部暂停，不作为本方案依赖。

## 0. 当前真实问题

用户已经完成了数据源 10、11、12 的：

```text
extract
deindex
grep
label
domains
style
```

但当前建 run 的代码：

```text
DeriveSampleService.create(RunCreateDTO)
```

内部读取的是全局素材池：

```java
List<Row> outlineRows = loadRows("OUTLINE", true);
List<Row> beatRows = loadRows("BEAT", true);
List<Row> worldRows = loadRows("WORLD", false);
```

没有把用户选择的数据源 10、11、12 作为 run 的取样边界。

所以当前系统存在一个真实业务缺口：

```text
用户处理了指定数据源
→ 但系统建 run 时仍从全局池取样
→ 无法证明这次 run 只使用了用户选定的数据源
```

这不是 SPLICE 问题，而是随机衍生 run 创建链路缺少 source selection。

## 1. 修复目标

建 run 时必须支持：

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

系统必须：

1. 只从 sourceIds 对应的无指向化素材池取样；
2. 把 sourceIds 持久化到 run；
3. `pickedOutlineIds`、`pickedBeatIds`、`pickedWorldId` 都能追溯到选定数据源；
4. 没有传 sourceIds 时保持旧行为，使用全局池；
5. 传了 sourceIds 但素材不足时明确报错，不得静默扩大到全局池。

## 2. 数据模型

### V142

在 derive 迁移目录新增：

```text
novelgen-data/src/main/resources/db/migration-derive/V142__run_source_ids.sql
```

内容：

```sql
ALTER TABLE run
    ADD COLUMN IF NOT EXISTS source_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
```

### RunDO

新增：

```java
private String sourceIds;
```

它保存 JSON 数组字符串，例如：

```json
[10,11,12]
```

### RunMapper XML

`insertFull` 增加：

```text
source_ids
```

写入必须显式：

```sql
#{p.sourceIds}::jsonb
```

不能走 MyBatis-Plus 默认 save。

## 3. RunCreateDTO

修改：

```java
public record RunCreateDTO(
        List<Long> sourceIds,
        Integer wordsMin,
        Integer wordsMax,
        List<String> tags,
        String pace,
        Long seed,
        String form,
        String outcome,
        Boolean antiRepro,
        String intent,
        String style,
        Boolean intakeOptimize,
        Boolean lenCheck
) {}
```

兼容规则：

- `sourceIds == null`：使用全局池；
- `sourceIds = []`：参数错误；
- `sourceIds` 有值：只使用这些数据源；
- 重复 ID 去重；
- ID 必须存在且已经完成素材处理。

## 4. DeriveSampleService 修改

### 4.1 入口

`create(RunCreateDTO dto)` 开头增加：

```java
List<Long> sourceIds = normalizeSourceIds(dto.sourceIds());
```

### 4.2 素材加载

把：

```java
loadRows("OUTLINE", true)
loadRows("BEAT", true)
loadRows("WORLD", false)
```

改为：

```java
loadRows("OUTLINE", true, sourceIds)
loadRows("BEAT", true, sourceIds)
loadRows("WORLD", false, sourceIds)
```

### 4.3 过滤规则

`AssetOutlineDO`、`AssetBeatDO`、`AssetWorldDO` 都有：

```java
srcNovelId
```

当 `sourceIds` 非空时，加载条件必须包含：

```java
AssetOutlineDO::getSrcNovelId in sourceIds
AssetBeatDO::getSrcNovelId in sourceIds
AssetWorldDO::getSrcNovelId in sourceIds
```

### 4.4 错误

如果选定数据源没有足够的：

- OUTLINE；
- BEAT；
- WORLD；

必须抛出明确错误：

```text
所选数据源没有足够的无指向化素材
sourceIds=...
outline=...
beat=...
world=...
```

不得回退全局池。

## 5. 可追溯性

建 run 后必须能够回答：

```text
这个 run 使用了哪些数据源？
抽中的 outline 来自哪个数据源？
抽中的 beat 来自哪个数据源？
抽中的 world 来自哪个数据源？
```

因此 `pickedOutlineIds`、`pickedBeatIds` 继续保存资产 ID；同时 `run.source_ids` 保存本次允许的来源范围。

验收 SQL 必须验证：

```sql
SELECT source_ids FROM run WHERE id = <runId>;
```

并验证抽中资产的：

```text
srcNovelId
```

全部属于 source_ids。

## 6. API 与前端

现有建 run 端点：

```text
POST /api/derive/runs
```

请求体增加：

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

前端建 run 页面必须：

- 先列出已处理完成的数据源；
- 允许用户多选；
- 把 sourceIds 放进请求；
- 显示本次 run 的来源范围；
- 不允许把未完成 ②③④ 的数据源混入选项。

## 7. 真实探针

修复后的真实探针不再调用 SPLICE 端点，而是调用随机衍生主链：

### 7.1 登录

Git Bash：

```bash
curl -sS -c var/splice-probe/cookies.txt \
  -X POST 'https://zzdzz.cn:8888/derive/api/auth/login' \
  -H 'Content-Type: application/json' \
  -H 'Origin: https://zzdzz.cn:8888' \
  --data-raw '{"username":"admin","password":"<真实口令>"}'
```

### 7.2 建 run

先将 JSON 写入 UTF-8 文件：

```bash
python -c "open('var/splice-probe/run.json','w',encoding='utf-8').write('{\"sourceIds\":[10,11,12],\"tags\":[\"悬疑\"],\"pace\":\"0.75\",\"seed\":450066,\"wordsMin\":12000,\"wordsMax\":18000}')"
```

再请求：

```bash
curl -sS -b var/splice-probe/cookies.txt \
  -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs' \
  -H 'Content-Type: application/json' \
  -H 'Origin: https://zzdzz.cn:8888' \
  --data-binary @var/splice-probe/run.json
```

### 7.3 启动自动流程

先 Read `DeriveRunController` 确认路径，然后使用真实路径：

```bash
curl -sS -b var/splice-probe/cookies.txt \
  -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs/<runId>/auto?direct=false' \
  -H 'Origin: https://zzdzz.cn:8888'
```

轮询：

```bash
curl -sS -b var/splice-probe/cookies.txt \
  'https://zzdzz.cn:8888/derive/api/derive/runs/<runId>/auto-progress' \
  -H 'Origin: https://zzdzz.cn:8888'
```

注意：上面模板中的 Origin 必须使用真实值，不能直接复制错误占位符。

### 7.4 必查结果

- run.source_ids 为 `[10,11,12]`；
- 抽中资产全部来自 10、11、12；
- run 进入 PLANNING；
- 后续 plan/draft/write/gate/qc 走既有 DeriveRunController；
- 不调用任何 SPLICE 端点；
- 不依赖 `SpliceService`；
- 不依赖文件工作区。

## 8. 测试

至少增加：

```java
void createRunUsesOnlySelectedSources()
void createRunRejectsEmptySourceList()
void createRunFallsBackToGlobalPoolWhenSourceIdsNull()
void pickedAssetsAllBelongToSelectedSources()
void selectedSourceWithoutLabeledAssetsFailsExplicitly()
```

回归：

```powershell
mvn -q test -pl novelgen-service -am
```

## 9. 完成标准

只有同时满足以下条件，才算修复：

- 数据源 10、11、12 可被用户选择；
- 建 run 请求可以携带 sourceIds；
- run.source_ids 真实落库；
- 取样只使用选定数据源；
- 未选数据源不会混入；
- 随机衍生主链可以正常进入 PLANNING；
- plan/draft/write/gate/qc 全部走既有 run 管线；
- 不再需要 SPLICE 端点；
- 真实探针不再被 G2 阻塞。

