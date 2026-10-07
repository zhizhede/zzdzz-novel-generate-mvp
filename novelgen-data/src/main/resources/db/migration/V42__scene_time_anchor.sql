-- 场景级时间锚（章内时序重构，2026-10-06）：
-- 章级 time_note 只能约束「本章距上一章」，章内场景先后（ch2 潜航舰出发时序颠倒）此前无账可依。
-- 时间锚是自由文本（「当夜」「封港半小时后」），由章纲/换皮 beats 产出、场景提示词与审校注入；
-- 可空——存量场景与模型漏字段一律 NULL，消费方回退章级 time_note（fail-open，旧书零变化）。
ALTER TABLE chapter_scenes ADD COLUMN time_anchor text;
