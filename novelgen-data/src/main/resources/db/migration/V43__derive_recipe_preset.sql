-- V43（2026-10-08）：derive_config 的 mode 键 → preset 键（业务侧推翻重构 M3，见 docs/adr/0001-rebuild-over-compat.md）。
-- D1 拍板：MIGRATE 产线下线，历史值一律归档 preset=RESKIN（能力表与旧 migrate() 双值语义一致，
-- 蓝图口径提示词段不丢）。读侧 Recipe.ofRaw 已兼容 mode/preset 两键，本脚本只清历史行、幂等
-- （mode 键消失后 WHERE 不再命中；重复执行 0 行）。
UPDATE novels
SET derive_config = (derive_config - 'mode')
    || jsonb_build_object('preset',
        CASE WHEN upper(btrim(derive_config ->> 'mode')) IN ('MIGRATE', 'RESKIN')
             THEN 'RESKIN'
             ELSE 'ORIGINAL' END)
WHERE derive_config ? 'mode';
