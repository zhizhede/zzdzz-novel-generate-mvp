-- 契约施工 V20（流0 停止 + 流A 打回 + 契约② 步骤状态行）
-- 详见 docs/design/契约施工设计.md §二

-- 1) 打回意见随章落库（注入章纲提示词，消费后清零）
ALTER TABLE chapters ADD COLUMN IF NOT EXISTS reject_reason text;

-- 2) 章节新状态 INTERRUPTED（用户硬停；重建 CHECK 约束，名以库内实际为准）
DO $$
DECLARE n text;
BEGIN
  SELECT conname INTO n FROM pg_constraint
   WHERE conrelid = 'chapters'::regclass AND contype = 'c'
     AND pg_get_constraintdef(oid) LIKE '%status%';
  IF n IS NOT NULL THEN EXECUTE 'ALTER TABLE chapters DROP CONSTRAINT ' || n; END IF;
END $$;
ALTER TABLE chapters ADD CONSTRAINT chapters_status_check
  CHECK (status IN ('NEW', 'OUTLINED', 'OUTLINE_APPROVED', 'DRAFTING',
                    'GATE_MECHANICAL', 'GATE_AI_REVIEW', 'REVISING',
                    'PENDING_APPROVAL', 'FINAL', 'DIGESTED', 'INDEXED', 'FAILED',
                    'INTERRUPTED'));

-- 3) 步骤状态表（契约②：断点/问责/一屏答案的地基；detail 为结构化失败原因 JSON 文本）
CREATE TABLE chapter_steps (
  id          BIGSERIAL PRIMARY KEY,
  novel_id    BIGINT NOT NULL,
  chapter_id  BIGINT NOT NULL REFERENCES chapters(id),
  chapter_no  INT    NOT NULL,
  step        VARCHAR(32) NOT NULL,
  sub_key     VARCHAR(32),
  attempt     INT    NOT NULL DEFAULT 1,
  status      VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  detail      TEXT,
  is_deleted  BOOLEAN     NOT NULL DEFAULT FALSE,
  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  delete_time TIMESTAMPTZ
);
CREATE INDEX idx_chapter_steps_chapter ON chapter_steps(chapter_id) WHERE is_deleted = FALSE;

-- 4) 任务扩展：取消落库（流0，替换内存 Set）+ 任务类型 + 插队标记（预留）
ALTER TABLE generation_tasks
  ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'CHAPTERS',
  ADD COLUMN IF NOT EXISTS payload JSONB,
  ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS pause_requested BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
DECLARE n text;
BEGIN
  SELECT conname INTO n FROM pg_constraint
   WHERE conrelid = 'generation_tasks'::regclass AND contype = 'c'
     AND pg_get_constraintdef(oid) LIKE '%status%';
  IF n IS NOT NULL THEN EXECUTE 'ALTER TABLE generation_tasks DROP CONSTRAINT ' || n; END IF;
END $$;
ALTER TABLE generation_tasks ADD CONSTRAINT generation_tasks_status_check
  CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'STOPPED', 'CANCELED', 'INTERRUPTED', 'PAUSED'));

-- 5) 打回意见长度护栏（流 A 设计决定 #5）
INSERT INTO tuning (tkey, tvalue, description)
VALUES ('reject_reason_max_len', '200', '打回意见注入章纲提示词的最大长度（字符），超长截断；前端输入框 maxlength 同源')
ON CONFLICT DO NOTHING;

-- 6) 复盘建议/提案（流 D 采纳 + 节点9 canon 提案承接）
CREATE TABLE retro_proposals (
  id          BIGSERIAL PRIMARY KEY,
  novel_id    BIGINT NOT NULL REFERENCES novels(id),
  vol_no      INT    NOT NULL,
  kind        VARCHAR(16) NOT NULL DEFAULT 'REVIEW',
  content     TEXT   NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
  decision_note TEXT,
  is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  delete_time TIMESTAMPTZ
);
CREATE INDEX idx_retro_proposals_vol ON retro_proposals(novel_id, vol_no) WHERE is_deleted = FALSE;
