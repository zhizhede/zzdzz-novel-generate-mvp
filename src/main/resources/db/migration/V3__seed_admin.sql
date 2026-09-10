-- 种子管理员：admin / admin123（哈希 = sha256(username:password)，与 AuthService 口径一致）
-- 上线前必须改密（UPDATE users SET password_hash = <sha256('admin:新密码')> WHERE username='admin'）
INSERT INTO users (username, password_hash, role)
VALUES ('admin', 'bf6b5bdb74c79ece9fc0ad0ac9fb0359f9555d4f35a83b2e6ec69ae99e09603d', 'admin')
ON CONFLICT (username) WHERE is_deleted = false
DO UPDATE SET password_hash = EXCLUDED.password_hash, update_time = NOW();
