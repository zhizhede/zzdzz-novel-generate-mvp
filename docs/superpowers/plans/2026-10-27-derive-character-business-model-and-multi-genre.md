# DeriveFlow 角色业务模型与多题材融合改造计划（交给独立上下文执行）

> 目标会话必须把本文档当作唯一执行规格。本文档不依赖聊天上下文；所有路径均为仓库相对路径。执行前先读 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md`、`docs/architecture/pipeline-contracts.md`。

## 0. 背景与禁止事项

当前随机衍生链路位于 `novelgen-service/src/main/java/com/zzdzz/novelgen/derive`，线上 derive 应用为 `novelgen-derive-app`，服务端口 8091，context-path `/derive`，数据库 `derive_gen`，宝塔服务器 `root@101.126.22.219`，SSH 私钥 `keys/101.126.22.219_id_ed25519`。

现有问题不是“推理小说合同缺失”，而是角色、关系、组织、世界制度和压力事件没有业务模型，全部退化成自由文本或标签。P6 现在只能检查结构和真相一致性，无法判断角色是否真的做出了符合自身目标、关系、组织压力和世界制度的选择。

本次改造必须遵守：

1. 不得按题材分别复制一套系统；所有题材共用通用角色业务模型，题材能力只能作为可选能力包。
2. 不得把角色建模为“职业标签/派系标签/刻板印象集合”。
3. 不得直接删除现有 `derive_character_plan`、`derive_story_thread`、`derive_volume_plan`、`beat_instance`、`derive_story_contract`；新增表并逐步迁移。
4. 不得用自由文本替代结构化状态；关键状态必须可查询、可校验、可回放。
5. 不得部署到宝塔、不得 commit/push，除非用户在执行会话中当轮明确允许。本文档只描述执行步骤，不代表已授权部署。
6. 不得修改 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md` 的既有铁律。
7. 所有 SQL 必须放在 `novelgen-data/src/main/resources/mapper` 对应 XML 或 Flyway migration；Java service 不得写 SQL。
8. MyBatis-Plus 不负责 jsonb 写入；jsonb 必须使用 XML 显式 `#{...}::jsonb`。
9. 改代码优先遵循现有 entity/DO、mapper、service、plan contract、test 命名模式。

## 1. 术语（执行会话必须使用这些术语）

- **角色档案（CharacterProfile）**：一个具体角色的目标、需求、矛盾、创伤、能力、限制、秘密、决策规则、说话和行为模式；不是职业标签。
- **关系事实（RelationshipFact）**：两个角色之间已经存在的公开/隐藏关系、信任、亲密、冲突、债务、历史和转折；不是只有一条 `REL-C1-C2` 字符串。
- **组织（Organization）**：具有公开目标、私有目标、资源、规则、弱点、压力方式和成员的具体组织；不是背景名词。
- **世界制度（WorldInstitution）**：法律、宗教教义、技术制度、社会规范等会实际影响角色选择的制度；不是世界观描述段落。
- **压力事件（PressureEvent）**：一个具体事件，必须说明表面事件、隐藏压力、触发者、受影响角色、状态前后、关系变化和角色变化。
- **状态账本（StoryStateLedger）**：按章节/拍顺序模拟角色、关系、组织和制度状态的确定性账本。
- **题材能力包（GenreCapability）**：可选的题材附加规则；通用模型不依赖推理、言情、奇幻等任何单一题材。
- **合同版本（ContractVersion）**：一次冻结的故事合同版本；下游只能引用当前版本，不能自行改写版本内容。

## 2. 当前代码与数据基线

### 2.1 已有表

执行前必须用 `psql` 重新核实以下表结构，禁止凭记忆猜列名：

- `run`
- `novels`
- `derive_character_plan`
- `derive_story_thread`
- `derive_volume_plan`
- `derive_chapter_contract`
- `beat_instance`
- `derive_story_contract`
- `derive_gate_review`
- `llm_call_log`

线上查询模板：

```bash
ssh -i keys/101.126.22.219_id_ed25519 root@101.126.22.219 \
  "docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -d derive_gen -c '\d derive_character_plan'"
```

### 2.2 已有代码

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStateLedgerService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`

### 2.3 当前已有合同表

`derive_story_contract` 已存在，字段为：

```text
run_id
version
status
truth_kernel
character_slots
event_kernel
clue_registry
knowledge_ledger
constraint_kernel
source_hash
```

本次不删除它。它作为“合同快照入口”保留，但不得继续承载所有角色业务细节；角色业务细节必须进入新表。

## 3. 数据库改造

Flyway 当前最新版本必须先在线上核实；若为 V131，则新迁移从 V132 开始。若线上版本不同，执行会话必须按真实最新版本顺延，不得覆盖历史迁移。

### 3.1 V132：角色档案

文件：`novelgen-data/src/main/resources/db/migration-derive/V132__derive_character_profile.sql`

```sql
CREATE TABLE derive_character_profile (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    character_key VARCHAR(16) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    public_goal TEXT NOT NULL,
    private_need TEXT NOT NULL,
    contradiction TEXT NOT NULL,
    wound TEXT,
    capability TEXT,
    limitation TEXT,
    secret TEXT,
    decision_rule TEXT NOT NULL,
    speech_pattern TEXT,
    behavior_pattern TEXT,
    current_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    arc_state VARCHAR(32) NOT NULL DEFAULT 'START',
    profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_character_profile_run_key UNIQUE (run_id, character_key)
);
CREATE INDEX idx_derive_character_profile_run ON derive_character_profile(run_id, character_key);
```

### 3.2 V133：关系事实

文件：`novelgen-data/src/main/resources/db/migration-derive/V133__derive_character_relationship.sql`

```sql
CREATE TABLE derive_character_relationship (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    from_character_key VARCHAR(16) NOT NULL,
    to_character_key VARCHAR(16) NOT NULL,
    relationship_type VARCHAR(32) NOT NULL,
    public_relation TEXT,
    hidden_relation TEXT,
    initial_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    target_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    debt TEXT,
    trust INTEGER NOT NULL DEFAULT 0,
    intimacy INTEGER NOT NULL DEFAULT 0,
    conflict INTEGER NOT NULL DEFAULT 0,
    history TEXT,
    current_turn TEXT,
    payoff TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_character_relationship UNIQUE (run_id, from_character_key, to_character_key),
    CONSTRAINT ck_derive_character_relationship_values CHECK (
        trust BETWEEN 0 AND 100 AND intimacy BETWEEN 0 AND 100 AND conflict BETWEEN 0 AND 100
    )
);
CREATE INDEX idx_derive_character_relationship_run ON derive_character_relationship(run_id, relationship_type);
```

### 3.3 V134：组织

文件：`novelgen-data/src/main/resources/db/migration-derive/V134__derive_organization.sql`

```sql
CREATE TABLE derive_organization (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    org_key VARCHAR(64) NOT NULL,
    org_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    public_goal TEXT NOT NULL,
    private_goal TEXT,
    resource TEXT,
    rule TEXT NOT NULL,
    weakness TEXT,
    pressure TEXT,
    members JSONB NOT NULL DEFAULT '[]'::jsonb,
    contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_organization_run_key UNIQUE (run_id, org_key)
);
CREATE INDEX idx_derive_organization_run ON derive_organization(run_id, org_type);
```

### 3.4 V135：世界制度

文件：`novelgen-data/src/main/resources/db/migration-derive/V135__derive_world_institution.sql`

```sql
CREATE TABLE derive_world_institution (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    institution_key VARCHAR(64) NOT NULL,
    institution_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    law_or_doctrine TEXT NOT NULL,
    enforcement TEXT NOT NULL,
    benefit TEXT,
    cost TEXT,
    affected_characters JSONB NOT NULL DEFAULT '[]'::jsonb,
    contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_world_institution_run_key UNIQUE (run_id, institution_key)
);
CREATE INDEX idx_derive_world_institution_run ON derive_world_institution(run_id, institution_type);
```

### 3.5 V136：压力事件

文件：`novelgen-data/src/main/resources/db/migration-derive/V136__derive_pressure_event.sql`

```sql
CREATE TABLE derive_pressure_event (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    event_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    title VARCHAR(256) NOT NULL,
    trigger_character_key VARCHAR(16),
    target_character_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    organization_key VARCHAR(64),
    institution_key VARCHAR(64),
    public_surface TEXT NOT NULL,
    hidden_pressure TEXT NOT NULL,
    before_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    relationship_changes JSONB NOT NULL DEFAULT '{}'::jsonb,
    character_changes JSONB NOT NULL DEFAULT '{}'::jsonb,
    chapter_no INTEGER,
    seq INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_pressure_event_run_key UNIQUE (run_id, event_key)
);
CREATE INDEX idx_derive_pressure_event_run ON derive_pressure_event(run_id, chapter_no, seq);
```

### 3.6 V137：题材能力包

文件：`novelgen-data/src/main/resources/db/migration-derive/V137__derive_genre_capability.sql`

```sql
CREATE TABLE derive_genre_capability (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    capability_key VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    required_contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    forbidden_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    event_roles JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_rules JSONB NOT NULL DEFAULT '{}'::jsonb,
    priority INTEGER NOT NULL DEFAULT 0,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_genre_capability_run_key UNIQUE (run_id, capability_key)
);
CREATE INDEX idx_derive_genre_capability_run ON derive_genre_capability(run_id, priority);
```

允许的能力键必须是稳定枚举，不接受自由字符串：

```text
WORLD
ROMANCE
MYSTERY
FANTASY
SCIFI
URBAN
IDOL
EPIC_FINALE
```

## 4. DO / Mapper 改造

每个新表必须增加 DO 和 Mapper，并遵循现有模块边界：

```text
novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/
├── DeriveCharacterProfileDO.java
├── DeriveCharacterRelationshipDO.java
├── DeriveOrganizationDO.java
├── DeriveWorldInstitutionDO.java
├── DerivePressureEventDO.java
└── DeriveGenreCapabilityDO.java

novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/
├── DeriveCharacterProfileMapper.java
├── DeriveCharacterRelationshipMapper.java
├── DeriveOrganizationMapper.java
├── DeriveWorldInstitutionMapper.java
├── DerivePressureEventMapper.java
└── DeriveGenreCapabilityMapper.java
```

每个 Mapper 都要有对应 XML；所有 JSONB 写入必须使用 XML 显式 cast。所有 DO 必须继承 `DeriveBaseDO`，时间列遵循现有 `created_time/updated_time` 约定。

## 5. 通用合同扩展

### 5.1 `derive_story_contract` 的使用边界

`derive_story_contract` 只保存合同快照：

- `truth_kernel`：仅当 `MYSTERY` 能力启用时使用；其他题材不得要求 `culprit/motive/method/revealChapter`。
- `character_slots`：只保存角色 key、槽位、最大数量、画外权限；具体人格进入 `derive_character_profile`。
- `event_kernel`：只保存事件 key、类型、章号、归属和依赖；具体压力进入 `derive_pressure_event`。
- `clue_registry`：仅当 `MYSTERY` 能力启用时使用。
- `knowledge_ledger`：保存知识事实和写入/读取角色。
- `constraint_kernel`：保存禁令、预算、融合裁决。

### 5.2 新合同字段

`truth_kernel` 中必须允许 `status=NONE`。推理以外的题材不得生成虚假的真凶/动机/手法。

`constraint_kernel` 中必须新增：

```json
{
  "maxNamedCharacters": null,
  "maxRelationships": null,
  "maxOrganizations": null,
  "maxInstitutions": null,
  "genreCapabilities": [],
  "fusionDecisions": []
}
```

`fusionDecisions` 每条必须有：

```json
{
  "decisionKey": "FUSION-001",
  "conflictingCapabilities": ["FANTASY", "MYSTERY"],
  "resolution": "奇幻能力可存在，但不得直接给出最终真相",
  "priority": ["MYSTERY_LOGIC", "FANTASY_WORLD"]
}
```

## 6. 服务层改造

### 6.1 新增服务

```text
DeriveCharacterProfileService
DeriveRelationshipService
DeriveOrganizationService
DeriveWorldInstitutionService
DerivePressureEventService
DeriveStoryStateLedgerService
DeriveGenreCapabilityService
```

### 6.2 `DeriveCharacterProfileService`

职责：

1. 接收角色名、题材能力、世界制度、组织和初始关系；
2. 生成/校验具体角色档案；
3. 要求每个角色必须有：
   - `public_goal`
   - `private_need`
   - `contradiction`
   - `decision_rule`
4. 不得只返回职业/派系/性格标签；
5. 如果模型只返回“偶像”“黑帮”“宗教信徒”等标签，必须判定为不合规并重试。

校验错误码：

```text
CHARACTER_GOAL_MISSING
CHARACTER_NEED_MISSING
CHARACTER_CONTRADICTION_MISSING
CHARACTER_DECISION_RULE_MISSING
CHARACTER_LABEL_ONLY
CHARACTER_UNKNOWN_ORGANIZATION
CHARACTER_UNKNOWN_INSTITUTION
```

### 6.3 `DeriveRelationshipService`

职责：

1. 生成关系事实；
2. 校验两个角色 key 必须存在；
3. 校验关系不是空文本；
4. 校验 `trust/intimacy/conflict` 在 0～100；
5. 校验关系必须有 `history` 或 `current_turn`；
6. 校验关系变化必须关联至少一个压力事件；
7. 恋爱能力启用时，关系必须有：
   - 起始状态；
   - 中段转折；
   - 结局或代价。

错误码：

```text
RELATIONSHIP_MISSING_CHARACTER
RELATIONSHIP_EMPTY
RELATIONSHIP_VALUE_OUT_OF_RANGE
RELATIONSHIP_NO_HISTORY
RELATIONSHIP_NO_TURN
RELATIONSHIP_ARC_INCOMPLETE
```

### 6.4 `DeriveOrganizationService`

职责：

1. 生成组织；
2. 校验组织必须有：
   - `public_goal`
   - `rule`
   - `pressure`
   - `weakness`
3. 组织成员必须来自角色档案；
4. 组织不能只是“派系名称”；
5. 组织必须至少能影响一个角色或一个制度。

错误码：

```text
ORGANIZATION_RULE_MISSING
ORGANIZATION_PRESSURE_MISSING
ORGANIZATION_WEAKNESS_MISSING
ORGANIZATION_UNKNOWN_MEMBER
ORGANIZATION_LABEL_ONLY
```

### 6.5 `DeriveWorldInstitutionService`

职责：

1. 生成法律/宗教/技术/社会制度；
2. 校验制度必须有：
   - `law_or_doctrine`
   - `enforcement`
   - `cost`
3. 制度必须影响至少一个角色；
4. 制度不能只是世界观说明。

错误码：

```text
INSTITUTION_LAW_MISSING
INSTITUTION_ENFORCEMENT_MISSING
INSTITUTION_COST_MISSING
INSTITUTION_NO_AFFECTED_CHARACTER
INSTITUTION_LABEL_ONLY
```

### 6.6 `DerivePressureEventService`

职责：

1. 从角色目标差、关系差、组织压力、制度代价生成压力事件；
2. 每个事件必须有：
   - 表面事件；
   - 隐藏压力；
   - 触发角色；
   - 受影响角色；
   - 状态前后；
   - 至少一个关系变化或角色变化；
3. 事件不能只写“发生冲突”“感情升温”“真相揭露”；
4. 必须能映射到 `beat_instance` 的 chapter/seq。

错误码：

```text
PRESSURE_EVENT_SURFACE_MISSING
PRESSURE_EVENT_HIDDEN_PRESSURE_MISSING
PRESSURE_EVENT_NO_TARGET
PRESSURE_EVENT_NO_STATE_CHANGE
PRESSURE_EVENT_NO_RELATION_OR_CHARACTER_CHANGE
PRESSURE_EVENT_UNMAPPED
```

### 6.7 `DeriveStoryStateLedgerService`

职责：

按 chapter/seq 顺序模拟：

```text
角色档案状态
关系状态
组织状态
世界制度状态
知识状态
```

输入：

```text
角色档案
关系事实
组织
世界制度
压力事件
拍合同
```

输出：

```text
每章前状态
每章后状态
每段关系变化
每个角色 arc 状态
未兑现事件
未闭合关系
未满足制度代价
```

状态模拟必须支持：

- 关系值增减；
- 角色 `arc_state` 迁移；
- 组织资源/压力变化；
- 制度成本触发；
- 知识写入与读取；
- 事件依赖。

状态值必须是结构化 JSON，不允许用一段自由文本代替。

## 7. 生成流程

执行会话必须按以下顺序实现：

### 阶段 A：能力包解析

入口：`DeriveSampleService.create` 完成采样、焊接、run 插入后。

步骤：

1. 根据 `tags`、`intent`、`outcome` 解析启用能力；
2. 至少保证 `WORLD` 能力启用；
3. 若 tags/intent 含言情/恋爱，启用 `ROMANCE`；
4. 若含悬疑/推理/案件/失踪/凶手，启用 `MYSTERY`；
5. 若含奇幻/魔法/异能/神明，启用 `FANTASY`；
6. 若含未来/科技/外星/改造，启用 `SCIFI`；
7. 若含都市/现代/城市，启用 `URBAN`；
8. 若含偶像/舞台/演艺，启用 `IDOL`；
9. 若用户要求大结局/国家级/世界级收束，启用 `EPIC_FINALE`；
10. 能力解析结果写入 `derive_genre_capability`，并写入合同快照。

### 阶段 B：世界制度与组织生成

入口：`DeriveStoryPlanService` 在 SCALE 后、SPINE 前。

步骤：

1. 读取 `derive_genre_capability`；
2. 调用世界制度生成；
3. 调用组织生成；
4. 校验制度/组织是否互相冲突；
5. 生成 `fusionDecisions`；
6. 冻结合同版本 `contractVersion=1`；
7. 未通过校验则 run 停在 `PLANNING`，不得进入 SPINE。

### 阶段 C：角色档案生成

入口：SPINE 后、角色计划生成前。

步骤：

1. 根据合同、组织、制度生成具体角色档案；
2. 每个角色必须有目标、需求、矛盾、决策规则；
3. 角色不得只是职业/派系标签；
4. 写入 `derive_character_profile`；
5. 校验失败则回退 `CHARACTER` 或 `CONTRACT`，不得进入卷规划。

### 阶段 D：关系事实生成

入口：角色档案完成后。

步骤：

1. 根据角色档案生成关系；
2. 每条关系必须有公开关系、隐藏关系、历史、转折；
3. 恋爱能力启用时必须生成恋爱关系弧；
4. 写入 `derive_character_relationship`；
5. 关系引用未知角色时回退 `CHARACTER`。

### 阶段 E：压力事件生成

入口：卷规划前。

步骤：

1. 计算角色目标差；
2. 计算关系冲突；
3. 计算组织压力；
4. 计算制度代价；
5. 生成压力事件；
6. 将事件映射到章/拍；
7. 写入 `derive_pressure_event`；
8. 生成事件账本；
9. 事件账本未闭合则不得进入 ⑥b。

### 阶段 F：卷合同与拍路由

卷合同和拍路由必须读取：

```text
角色档案
关系事实
组织
世界制度
压力事件
状态账本
```

不得只读取旧的：

```text
derive_character_plan.function_code
derive_story_thread.thread_key
volume_plan.trueAnswer
```

旧表继续保留兼容，但新字段必须成为生成和校验的主来源。

### 阶段 G：⑥b 具体稿

`DeriveDraftService` 的输入必须包含：

```text
角色档案
关系事实
组织
世界制度
压力事件
当前状态账本
题材能力包
```

每章提示词必须明确：

```text
本章事件
本章表面事件
本章隐藏压力
本章角色选择
本章关系变化
本章制度代价
本章组织压力
```

禁止模型只按：

```text
HOOK / CLUE_PLANT / RELATION_CONFLICT
```

生成空洞拍。

### 阶段 H：P6

P6 必须拆成：

#### P6-A：合同与状态一致性

确定性检查：

- 角色档案字段齐全；
- 关系字段齐全；
- 组织字段齐全；
- 制度字段齐全；
- 压力事件字段齐全；
- 状态账本无未解释变化；
- 组织成员、制度影响角色、关系角色全部闭合；
- 事件已映射到章/拍；
- 能力包冲突已记录 `fusionDecisions`。

P6-A 失败不进入 P6-B，且不消耗 P6-B 语义轮次。

#### P6-B：语义质量

只检查：

- 角色选择是否符合 `decision_rule`；
- 关系变化是否有事件依据；
- 组织压力是否实际改变了选择；
- 制度代价是否实际发生；
- 角色 arc 是否从 START 到 PAYOFF；
- 结局是否兑现角色、关系、组织和制度；
- 文风/氛围是否符合目标风格。

## 8. 题材能力包的具体规则

### 8.1 `WORLD`

必须存在：

```text
WorldInstitution
至少一个 Organization
至少一条角色-制度影响
```

### 8.2 `ROMANCE`

必须存在：

```text
至少一条 RelationshipFact
关系有起始、转折、结局或代价
至少一个 PressureEvent 改变关系状态
```

禁止：

```text
无事件支撑的突然相爱
无代价的完美结局
把恋爱线写成只加好感度
```

### 8.3 `MYSTERY`

只在该能力启用时要求：

```text
TruthKernel
ClueRegistry
KnowledgeLedger
```

禁止把 `culprit/motive/method/revealChapter` 强加给非推理题材。

### 8.4 `FANTASY`

必须存在：

```text
世界规则
能力或超自然机制
代价或限制
```

禁止：

```text
无代价能力
能力直接替代人物选择
能力直接给出真相
```

### 8.5 `SCIFI`

必须存在：

```text
技术制度
技术代价
角色与技术制度的冲突
```

### 8.6 `IDOL`

必须存在：

```text
偶像组织
舞台/演艺资源
观众或传播机制
偶像职业目标与私人需求的矛盾
至少一个关系事件影响舞台或组织
```

禁止：

```text
只写“闪耀舞台”“元气满满”
只写组织名称，不写组织规则
只写恋爱，不写职业代价
```

### 8.7 `EPIC_FINALE`

必须存在：

```text
至少一个国家级/世界级制度
至少一个宏观组织
至少一个角色关系 payoff
至少一个世界规则 payoff
最终事件同时兑现宏观冲突和个人选择
```

## 9. 风格与氛围模型

风格不能只存一段提示词。新增合同快照字段或单独表均可，但必须结构化：

```json
{
  "styleKey": "KIRA_KIRA_MODERN_URBAN",
  "visualMotifs": ["霓虹", "舞台灯", "雨夜玻璃", "高速剪辑"],
  "dialogueTexture": ["短句", "抢白", "高情绪密度"],
  "sceneTexture": ["高光舞台", "后台沉默", "城市夜景"],
  "emotionalPressure": ["公开闪耀", "私下崩溃"],
  "forbiddenTextures": ["冷硬公文腔", "纯说明文"]
}
```

P6-B 必须按 `styleKey` 检查，不得把“kilakila”当作风格验收的唯一标签。

## 10. 测试计划

### 10.1 单元测试

新增：

```text
DeriveCharacterProfileServiceTest
DeriveRelationshipServiceTest
DeriveOrganizationServiceTest
DeriveWorldInstitutionServiceTest
DerivePressureEventServiceTest
DeriveStoryStateLedgerServiceTest
DeriveGenreCapabilityServiceTest
DeriveCharacterBusinessModelContractTest
```

必须覆盖：

1. 只有职业标签的角色被拒绝；
2. 缺少 `decision_rule` 的角色被拒绝；
3. 关系缺历史或转折被拒绝；
4. 关系值超出 0～100 被拒绝；
5. 组织缺规则/压力/弱点被拒绝；
6. 制度缺执行方式/代价被拒绝；
7. 压力事件缺隐藏压力被拒绝；
8. 压力事件没有状态变化被拒绝；
9. 状态账本能正确模拟关系值变化；
10. 状态账本能正确模拟角色 arc 迁移；
11. `MYSTERY` 未启用时不要求真凶；
12. `MYSTERY` 启用时要求真相核；
13. `ROMANCE` 启用时要求关系弧；
14. `FANTASY` 启用时要求能力代价；
15. `IDOL` 启用时要求组织、舞台资源和职业/私人矛盾；
16. `EPIC_FINALE` 启用时要求宏观制度和个人 payoff 同时闭合；
17. 能力冲突必须产生 `fusionDecision`；
18. P6-A 失败时不得进入 P6-B。

### 10.2 回归测试

必须保留并通过现有：

```text
DerivePlanValidationServiceTest
DeriveGateServiceTest
DeriveStoryPlanTruthContractTest
DeriveBeatRouteServiceTest
DeriveDraftBatchTest
LlmJsonTest
```

### 10.3 构建验收

按顺序执行：

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'
mvn -q -DskipTests compile
mvn -q test
mvn -q -pl novelgen-derive-app -am -DskipTests package
```

必须确认：

- 编译通过；
- 全量测试通过；
- derive jar 生成；
- 无密钥进入 git；
- 无 commit/push。

## 11. 部署验收（仅当用户当轮明确授权）

未经授权不得执行。授权后：

1. 记录当前线上 Flyway 版本；
2. 备份 `derive_gen` 的：
   - `run`
   - `novels`
   - `derive_character_plan`
   - `derive_story_thread`
   - `derive_volume_plan`
   - `derive_chapter_contract`
   - `beat_instance`
   - `derive_story_contract`
3. 停止 `novelgen-derive`；
4. 上传 derive jar；
5. 启动 `novelgen-derive`；
6. 检查服务 active；
7. 检查 Flyway 新迁移 success；
8. 创建一个包含多题材标签的测试 run；
9. 记录：
   - 能力包；
   - 制度；
   - 组织；
   - 角色档案；
   - 关系；
   - 压力事件；
   - 状态账本；
   - P6-A；
   - P6-B；
   - 最终状态。

## 12. 验收定义

本次任务只有在以下条件全部满足时才算完成：

1. 新表全部建好；
2. DO/Mapper/XML 全部存在；
3. 角色档案不是职业标签；
4. 关系是结构化事实；
5. 组织和世界制度能影响角色；
6. 压力事件能改变角色和关系状态；
7. 状态账本可回放；
8. 多题材能力包可组合；
9. 非推理题材不要求真相核；
10. `MYSTERY` 启用时才要求真相核；
11. P6-A 和 P6-B 分离；
12. P6-A 失败不进入 P6-B；
13. 测试和构建全部通过；
14. `docs/STATUS.md` 已追加本次执行记录；
15. 没有未经用户授权的 commit、push、部署。

## 13. 交付物清单

独立执行会话必须在结束时报告：

```text
数据库迁移文件路径
DO 文件路径
Mapper 文件路径
XML 文件路径
新增服务文件路径
修改的现有服务文件路径
新增测试文件路径
mvn test 结果
derive jar SHA256
STATUS.md 更新位置
是否部署
测试 run id
P6-A/P6-B 结果
剩余问题
```

如果任何验收项未完成，必须明确报告未完成项，不得把“设计完成”写成“实现完成”。

## 14. Agent-in-the-loop 协商工作流（不是全链路 Agent 化）

### 14.1 设计判断

不能把所有判断都交给 Agent，也不能全部交给确定性规则。

- 确定性代码负责：字段完整性、枚举、主外键、数值范围、章号/seq、状态前后闭合、能力包硬规则、版本和回放。
- Agent 负责：角色目标与矛盾设计、关系取舍、组织压力设计、制度代价设计、压力事件方案、语义质量评审、冲突解释和局部修订。
- Agent 只能提出 `Proposal`，不能直接把任意文本写成最终合同。
- Orchestrator 负责把多个 Proposal 合并、校验、落库、重试和终止。

### 14.2 新增 Agent 工作流表

在 V132-V137 之后新增：

文件：`novelgen-data/src/main/resources/db/migration-derive/V138__derive_agent_workflow.sql`

```sql
CREATE TABLE derive_agent_task (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    contract_version INTEGER NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    input_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempt INTEGER NOT NULL DEFAULT 3,
    last_error TEXT,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_agent_task_run_key UNIQUE (run_id, task_key)
);

CREATE TABLE derive_agent_proposal (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    contract_version INTEGER NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    proposal_key VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    proposal_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    assumptions JSONB NOT NULL DEFAULT '[]'::jsonb,
    open_questions JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_agent_proposal_run_task_key UNIQUE (run_id, task_key, proposal_key),
    CONSTRAINT ck_derive_agent_proposal_confidence CHECK (confidence BETWEEN 0 AND 100)
);

CREATE TABLE derive_agent_deliberation (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    contract_version INTEGER NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    round_no INTEGER NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    selected_proposal_key VARCHAR(64),
    decision JSONB NOT NULL DEFAULT '{}'::jsonb,
    unresolved JSONB NOT NULL DEFAULT '[]'::jsonb,
    repair_target VARCHAR(32) NOT NULL DEFAULT 'NONE',
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT
);
CREATE INDEX idx_derive_agent_deliberation_run ON derive_agent_deliberation(run_id, task_key, round_no DESC);
```

### 14.3 Agent 输出契约

所有 Agent 必须输出结构化 JSON，禁止直接输出最终数据库行。

统一字段：

```json
{
  "proposalKey": "P-001",
  "proposalType": "CHARACTER_PROFILE",
  "payload": {},
  "evidence": [
    "依据合同中的组织规则 X",
    "依据角色档案中的决策规则 Y"
  ],
  "assumptions": [
    "假设 C1 的公开目标优先于组织任务"
  ],
  "openQuestions": [
    "C2 是否知道 C1 的秘密"
  ],
  "confidence": 78
}
```

Agent 不能省略：

```text
proposalKey
proposalType
payload
evidence
assumptions
openQuestions
confidence
```

校验失败的 Proposal 不得进入 deliberation。

### 14.4 Orchestrator 责任

新增：

```text
DeriveAgentOrchestratorService
```

职责：

1. 从 `derive_agent_task` 认领任务；
2. 组装确定性输入；
3. 调用一个或多个 Agent；
4. 将输出写入 `derive_agent_proposal`；
5. 运行确定性校验；
6. 必要时发起下一轮协商；
7. 将最终选择写入 `derive_agent_deliberation`；
8. 生成合同修订；
9. 触发下游状态账本重算。

Agent 不能直接调用 `runMapper.updateStatus`、直接改合同、直接改关系状态。

### 14.5 角色档案协商流

任务：

```text
CHARACTER_PROFILE
```

参与角色：

```text
PLANNER
PSYCHOLOGIST
CRITIC
REVISER
ARBITER
```

流程：

```text
PLANNER 提出角色档案
  ↓
PSYCHOLOGIST 检查目标/需求/矛盾是否构成真实选择
  ↓
CRITIC 找出标签化、空泛、无代价、与组织/制度冲突的问题
  ↓
REVISER 生成修订版
  ↓
ARBITER 在冲突时选择方案或提出折中
  ↓
确定性校验
  ↓
冻结角色档案
```

示例任务输入：

```json
{
  "capabilities": ["IDOL", "ROMANCE", "SCIFI"],
  "organizations": ["星环演艺联合体"],
  "institutions": ["改造人登记法"],
  "initialRole": "C1"
}
```

示例 Proposal payload：

```json
{
  "characterKey": "C1",
  "publicGoal": "让团队完成首次世界巡演",
  "privateNeed": "确认自己不只是被包装出来的商品",
  "contradiction": "她需要观众的掌声，但又不相信掌声属于真实的自己",
  "decisionRule": "当组织要求牺牲队友时，她会选择违反命令但不公开背叛团队",
  "wound": "出道前因为真实情绪被制作人否定",
  "capability": "能在身体极限下维持舞台表现",
  "limitation": "无法在公开场合承认想退出",
  "secret": "她正在准备离开偶像体系"
}
```

CRITIC 不能只说“角色不够立体”，必须返回结构化问题：

```json
{
  "issues": [
    {
      "code": "CHARACTER_NO_COST",
      "message": "publicGoal 没有说明失败代价",
      "repairTarget": "CHARACTER"
    }
  ]
}
```

### 14.6 关系协商流

任务：

```text
RELATIONSHIP_FACT
```

参与角色：

```text
PERSPECTIVE_FROM
PERSPECTIVE_TO
RELATIONSHIP_PLANNER
CRITIC
ARBITER
```

流程：

```text
C1 视角 Agent 描述其对 C2 的目标、恐惧、债务和信任
  ↓
C2 视角 Agent 描述其对 C1 的目标、恐惧、债务和信任
  ↓
Relationship Planner 对齐双方视角
  ↓
Critic 检查是否变成单向付出、突然相爱、无历史转折
  ↓
Arbiter 解决视角冲突
  ↓
确定性校验关系值和角色闭合
```

这样关系不是系统直接写：

```text
C1-C2 恋爱
```

而是从两个角色的各自目标、恐惧和历史中协商出来。

示例关系 payload：

```json
{
  "from": "C1",
  "to": "C2",
  "relationshipType": "恋人未满",
  "publicRelation": "同一偶像组合成员",
  "hiddenRelation": "C2 知道 C1 准备退出但一直保密",
  "initialState": {"trust": 70, "intimacy": 60, "conflict": 20},
  "targetState": {"trust": 30, "intimacy": 80, "conflict": 70},
  "debt": "C2 曾替 C1 向公司隐瞒一次舞台事故",
  "history": "两人在练习生时期互相支撑，但从未谈过未来",
  "currentTurn": "C2 发现 C1 已报名海外剧团",
  "payoff": "C2 放她离开，但要求她亲口承认自己想要什么"
}
```

### 14.7 组织与制度协商流

任务：

```text
ORGANIZATION_PRESSURE
WORLD_INSTITUTION
```

参与角色：

```text
INSTITUTION_AUTHOR
ORGANIZATION_REPRESENTATIVE
AFFECTED_CHARACTER
CRITIC
ARBITER
```

流程：

```text
制度 Agent 提出制度、执行方式和代价
  ↓
组织 Agent 解释组织如何利用/违反/维护制度
  ↓
受影响角色 Agent 说明制度如何压缩其选择
  ↓
Critic 检查制度是否只是背景说明
  ↓
Arbiter 解决制度和组织目标冲突
  ↓
生成 PressureEvent 候选
```

组织和制度不能由一个 Agent 直接写死，因为它们必须至少包含两个视角：

```text
谁制定/维护制度
谁受到制度压迫
谁从制度获益
```

### 14.8 Pressure Event 协商流

任务：

```text
PRESSURE_EVENT
```

参与角色：

```text
TRIGGER_AGENT
TARGET_AGENT
RELATIONSHIP_AGENT
CRITIC
ARBITER
```

Agent 不直接生成“剧情拍”，而是生成事件提案：

```json
{
  "eventType": "RELATIONSHIP_PRESSURE",
  "title": "公司要求公开恋爱关系",
  "triggerCharacter": "C2",
  "targetCharacters": ["C1", "C2"],
  "organization": "星环演艺联合体",
  "institution": "偶像成员关系报备制度",
  "publicSurface": "公司要求所有一线成员提交关系声明",
  "hiddenPressure": "C1 如果承认恋情，就会失去退出保护",
  "beforeState": {
    "C1.trustInC2": 70,
    "C2.knowsC1QuitPlan": true
  },
  "afterState": {
    "C1.trustInC2": 40,
    "C2.feelsBetrayed": true
  },
  "relationshipChanges": {
    "REL-C1-C2": {"trust": -30, "conflict": 40}
  },
  "characterChanges": {
    "C1": {"arcState": "HIDDEN_TRUTH_REVEALED"}
  }
}
```

确定性校验要求：

- `event_key` 唯一；
- 触发角色存在；
- 目标角色存在；
- `before_state` 到 `after_state` 至少有一项变化；
- `relationship_changes` 或 `character_changes` 至少一个非空；
- 关系 key 必须存在于关系事实；
- 角色 key 必须存在于角色档案；
- 组织/制度 key 若填写则必须存在；
- 事件能映射到 chapter/seq。

### 14.9 P6 的 Agent 工作流

P6-A 继续保持确定性，不交给 Agent：

```text
字段完整性
主外键闭合
数值范围
章号/seq
状态账本硬错误
能力包硬规则
```

P6-B 使用 Agent 协商，但不拥有最终放行权。

P6-B 参与角色：

```text
CHARACTER_CRITIC
RELATIONSHIP_CRITIC
WORLD_CRITIC
STYLE_CRITIC
ARBITER
```

每个 Critic 必须输出：

```json
{
  "issueCode": "CHARACTER_DECISION_INCONSISTENT",
  "severity": "FIXABLE",
  "evidence": [
    "第 6 章 C1 的选择违反 decision_rule",
    "第 5 章组织压力没有导致任何状态变化"
  ],
  "repairTarget": "CHARACTER"
}
```

Arbiter 只能：

- 选择一个 Proposal；
- 合并局部修订；
- 提出结构化 unresolved issue；
- 不能把 `HARD` 问题改成 `PASS`。

Java 最终裁决仍遵循：

```text
存在 HARD/FIXABLE → REWORK 或 FAILED
只有 ADVISORY → PASS
```

### 14.10 Agent 轮次与熔断

每个任务最多 3 次：

```text
attempt 1: Proposal
attempt 2: Revision after Critic
attempt 3: Arbiter final proposal
```

超限后：

```text
status=FAILED
repairTarget=确定性代码解析出的最上游层
```

不允许：

- Agent 无限协商；
- Agent 直接改数据库；
- Agent 通过自然语言绕过合同；
- Agent 输出自由文本后由代码猜字段。

### 14.11 为什么不能全交给 Agent

以下内容必须由确定性代码处理：

```text
章号连续
seq 唯一
角色 key 闭合
关系 key 闭合
组织成员闭合
制度影响角色闭合
关系值范围
事件状态前后变化
知识写入早于读取
合同版本一致
P6-A 失败不进入 P6-B
```

以下内容才交给 Agent：

```text
角色目标与矛盾
关系视角冲突
组织压力解释
制度代价解释
压力事件方案
风格与氛围判断
语义质量批评
冲突方案选择
```

## 15. 验收补充（Agent 工作流）

在第 12 节验收定义之外，必须增加：

1. `derive_agent_task` 可创建、认领、重试、失败；
2. `derive_agent_proposal` 能保存 evidence/assumptions/openQuestions/confidence；
3. `derive_agent_deliberation` 能保存每轮 verdict、selected proposal、unresolved；
4. 角色档案必须经过至少一次 Critic；
5. 关系事实必须经过至少一次双视角协商；
6. 组织/制度必须经过至少一个受影响角色视角；
7. PressureEvent 必须有状态前后变化；
8. P6-A 确定性失败时不调用 P6-B Agent；
9. P6-B Agent 不能直接产生 `PASS`；
10. Agent 失败必须留下结构化 `last_error`；
11. 全量测试必须覆盖 Agent Orchestrator 的正常、修订、熔断路径；
12. 报告中必须列出 Agent 轮次、Proposal 数量、未解决问题和最终 repairTarget。
