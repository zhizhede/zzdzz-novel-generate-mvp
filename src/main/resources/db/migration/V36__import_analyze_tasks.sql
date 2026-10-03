-- V36 · 导入书籍后的「解析链」任务：把素材库能通过 LLM 生成的东西 + 大纲/卷纲/章纲做成可选、可续跑的链。
-- 口径：一本活书一行活跃任务（重复提交＝重置同一行，不新增）；每步结果落 done_steps JSON，前端按步渲染进度。
-- 为什么要有这张表：单步动辄几十秒到十几分钟（逐章事实账 / 卷规划），必须异步且有断点事实，不能挂在 HTTP 请求上。
CREATE TABLE import_analyze_tasks (
    id           BIGSERIAL PRIMARY KEY,
    novel_id     BIGINT NOT NULL REFERENCES novels(id),
    steps        JSONB NOT NULL,                        -- 勾选并已排序的步骤键：["DIGESTS","OUTLINE",...]
    status       VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'INTERRUPTED')),
    current_step VARCHAR(32),                           -- 正在跑的步骤键（进度条用）
    done_steps   JSONB NOT NULL DEFAULT '[]'::jsonb,    -- 逐步结果：[{step,status,message,elapsedMs,counts}]
    message      VARCHAR(512),
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    create_time  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_import_analyze_task_alive ON import_analyze_tasks(novel_id) WHERE is_deleted = FALSE;
