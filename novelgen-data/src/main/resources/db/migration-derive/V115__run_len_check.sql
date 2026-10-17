-- V115: run.len_check——每章字数带（budget±10%）校验开关，**默认 FALSE=关**（2026-10-16 用户定调）
-- 关=⑦ 不再因字数带打回/修整（首稿直接落库），⑧ 第2项降报告不判负；总字数第1项与禁词/主角名 gate 不受影响。
ALTER TABLE run ADD COLUMN len_check BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN run.len_check IS '每章字数带校验开关（默认 FALSE=关）：关=⑦ 跳过±10% gate（无字数修整轮）、⑧「2每章字数」降报告；开=原口径。总字数「1总字数」恒开。';
