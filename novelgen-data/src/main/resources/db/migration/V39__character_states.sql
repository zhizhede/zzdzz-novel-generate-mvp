-- V39 · character_states：人物状态账（2026-10-05）——把「不可查的 jsonb」变成可查的表。
--
-- 背景：跨章一致性此前只有 world_states.state 一个 jsonb（键 time/locations/possessions/
-- new_promises/unresolved），其中 locations={人名或物名: 所在位置}、possessions={人名:[随身物]} 本来就是
-- **按人组织**的，但存在 jsonb 里只能整块读、没法按人查、没法跨章比对，于是「谁在哪、谁带着什么、
-- 谁已经死了」这类核对只能靠模型重读全文。本表是那份 jsonb 的**关系投影**：不新增 LLM 调用，
-- 由 digest 落库时顺带投影（见 CharacterStateService.project），存量书用 backfill 补齐。
--
-- 口径：
--   · 一行 = 一个名字在**某一章结束时**的状态（novel_id + chapter_no + name 唯一）；
--   · name 可以是人名也可以是重要物名（digest 规格就是「人名或重要物名」），不在这里区分——
--     区分靠素材卡/场景 present 名单，混进来的地名物名不影响按人核对；
--   · location 是原文短句（可能很长，如「已死，眼未合」），故放宽到 512 且允许 NULL；
--   · possessions 仍留 jsonb（就是一个字符串数组，没有按物查询的需求，别为对称硬拆表）。
--
-- 为什么与 world_states 同形（只有 novel_id + chapter_no，没有 chapter_id）：
-- 这张表是 world_states 的投影，两者必须能按同键对齐；也避免多一条「章删了账还在不在」的分叉语义。
-- 删书级联清账（novel_id 外键 ON DELETE CASCADE，本表是 V37 之后新建，故自带级联）。

CREATE TABLE character_states (
    id          BIGSERIAL PRIMARY KEY,
    novel_id    BIGINT      NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    chapter_no  INTEGER     NOT NULL,
    name        VARCHAR(128) NOT NULL,
    location    VARCHAR(512),
    possessions JSONB,
    is_deleted  BOOLEAN     NOT NULL DEFAULT false,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_time TIMESTAMPTZ
);

-- 软删已下线（is_deleted 是死列），谓词恒为真——沿用全库条件唯一索引的写法保持一致
CREATE UNIQUE INDEX uq_character_states_row_alive ON character_states (novel_id, chapter_no, name) WHERE is_deleted = false;
CREATE INDEX idx_character_states_name ON character_states (novel_id, name, chapter_no);
