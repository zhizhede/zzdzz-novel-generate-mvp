-- V38 · 成品终检关的处置开关（2026-10-05）：评审修订后机械复检未过时怎么办。
--
-- 背景：读者评审 / AI 审校的「带清单修订」会绕开章级机械门禁重写正文（第 3 章实测 行均长 17.97→21.50），
-- 管线原先只做一件事——复检、把漂移写进报告与事件、然后**照样保留修订稿**。也就是风格门禁在成品这一环
-- 事实上是「只记录不拦截」。这个开关把处置策略交出来，默认值保持原行为（放行并标记），改口径不必改代码。
--
-- gate_recheck_action 取值（大小写不敏感，取值非法回退 KEEP）：
--   KEEP     = 放行并标记（默认，原行为）：内容修订优先级高于指纹，避免改写循环；
--   ROLLBACK = 回退：若修订前那版能过机械门禁，就用回修订前的稿（宁可带内容瑕疵也不要风格漂移）；
--   REVISE   = 再修订：拿机械失败清单再修订 N 轮（gate_recheck_revise_rounds），过了才换，没过仍按 KEEP 处理。
--
-- 书级覆盖走 style_packs.gate_config 同名键（GateService.configText 读法：gate_config > tuning > 代码默认）。

INSERT INTO tuning (tkey, tvalue, description) VALUES
    ('gate_recheck_action', 'KEEP', '评审修订后机械复检未过的处置：KEEP=放行并标记（默认）/ ROLLBACK=回退到修订前 / REVISE=再修订'), 
    ('gate_recheck_revise_rounds', '1', 'gate_recheck_action=REVISE 时的最大再修订轮数（过了才换稿）');
