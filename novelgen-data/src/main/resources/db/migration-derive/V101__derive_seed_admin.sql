-- V101 · 衍生库种子管理员
-- 口令不入库——哈希 = sha256(username:password)，与 AuthService 口径一致（同 novel_gen V3）
-- 种子哈希 = sha256('admin:admin123')，与 novel_gen 现行口令一致（两边同口令）；首启后可自行改：
--   UPDATE users SET password_hash = encode(digest(('admin:' || '<自设口令>')::bytea, 'sha256'), 'hex') WHERE username = 'admin';
-- 本库 users 由 V100 建（无 is_deleted，普通唯一约束），冲突目标 = 普通唯一
INSERT INTO users (username, password_hash, role)
VALUES ('admin', 'bf6b5bdb74c79ece9fc0ad0ac9fb0359f9555d4f35a83b2e6ec69ae99e09603d', 'admin')
ON CONFLICT (username)
DO UPDATE SET password_hash = EXCLUDED.password_hash, update_time = NOW();
