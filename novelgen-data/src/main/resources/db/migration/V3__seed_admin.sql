-- 种子管理员：admin（口令不入库——哈希 = sha256(username:password)，与 AuthService 口径一致）
-- 新装实例首次启动后必须自行设置口令：
--   UPDATE users SET password_hash = encode(digest(('admin:' || '<自设口令>')::bytea, 'sha256'), 'hex') WHERE username = 'admin';
INSERT INTO users (username, password_hash, role)
VALUES ('admin', '2678323ba5602257f4ea5b5f1e60f1e0c625185e7b3a653d21511a745e9562ed', 'admin')
ON CONFLICT (username) WHERE is_deleted = false
DO UPDATE SET password_hash = EXCLUDED.password_hash, update_time = NOW();
