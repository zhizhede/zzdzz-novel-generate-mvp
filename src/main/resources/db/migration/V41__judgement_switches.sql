-- V41 · 判据/口径开关（2026-10-05）：把「第三类·判据不可靠」与「第四类·产品定调」的两处判断交出来。
--
-- 这两类问题没有技术正确答案（第三类靠模型主观判断、第四类取决于产品想要什么稿），
-- 基座早已就绪（tuning 全局 + style_packs.gate_config 书级覆盖），缺的只是把硬编码的口径变成可调键。
--
-- 1) reader_repeat_fix_min ≤0 ＝关闭去复沓修订。
--    注意原先写 0 会**反向**生效：判据是 `repeat.size() >= minRepeat`，minRepeat=0 时恒真 ⇒ 每章都治。
--    本次把「≤0＝关」写进判据（与 foreshadow_proposed_max_age / stream_long_text 等开关一致），
--    库里默认仍是 3。复沓零容忍口径实测会打到人类出版原文（第三类的典型例子），要关就改这个键。
--
-- 2) reader_structural_block：读者评审的结构性四问（hook/stakes/continuity/consequence）未过时是否拦章。
--    默认 1（拦，现状）；置 0 则降级为报告项（照常落报告与事件，但不阻塞过稿）。
--    fat_ratio 超**硬上限**不受此开关影响——那不是口径问题，是注水。

INSERT INTO tuning (tkey, tvalue, description) VALUES
    ('reader_repeat_fix_min', '3', '读者评审复沓清单触发去复沓修订的最低条数；≤0＝关闭该修订（复沓零容忍口径实测会打到人类原文）'),
    ('reader_structural_block', '1', '读者评审结构性四问未过时是否拦章：1=拦（默认）；0=只报不拦（判据主观，交产品定调）')
ON CONFLICT (tkey) WHERE is_deleted = false DO UPDATE SET description = EXCLUDED.description;
