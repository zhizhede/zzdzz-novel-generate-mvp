package com.zzdzz.novelgen.llm;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API key 库内密文对称加密（AES-256-GCM）：随机 12 字节 IV 每次独立，密文=base64(iv||ct+tag)。
 * 主密钥来自 novelgen.llm.master-key（32 字节 base64，application-local.yaml）——与库分离，
 * 换库/拖库拿不到明文。master-key 未配置时在首次加解密处 fail-fast（人话报错，不静默降级）。
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(LlmProperties props) {
        String mk = props.masterKey();
        if (mk == null || mk.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(mk.strip());
        if (raw.length != 32) {
            throw new IllegalStateException("novelgen.llm.master-key 必须是 32 字节的 base64（openssl rand -base64 32 生成），当前解码后 " + raw.length + " 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 未配置主钥时给出可行动报错（而非 NPE/静默）。 */
    private SecretKey requireKey() {
        if (key == null) {
            throw new IllegalStateException("novelgen.llm.master-key 未配置——使用「模型接入」落库前先在 application-local.yaml 生成并配置主钥");
        }
        return key;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("API key 加密失败: " + e.getMessage(), e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(),
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API key 解密失败——密文损坏或 master-key 与加密时不一致", e);
        }
    }
}
