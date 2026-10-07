package com.zzdzz.novelgen.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API key 库内密文加密：往返一致、IV 随机（同明文不同密文）、篡改必炸、无主钥 fail-fast。 */
class SecretCipherTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private SecretCipher cipher(String masterKey) {
        return new SecretCipher(new LlmProperties(
                "https://api.example.com", "sk-x", "m1", Duration.ofSeconds(10), Duration.ofSeconds(60), masterKey));
    }

    @Test
    void roundtrip() {
        SecretCipher c = cipher(MASTER_KEY);
        String enc = c.encrypt("sk-live-abc123");
        assertThat(enc).doesNotContain("sk-live");
        assertThat(c.decrypt(enc)).isEqualTo("sk-live-abc123");
    }

    @Test
    void samePlaintextYieldsDifferentCiphertext() {
        SecretCipher c = cipher(MASTER_KEY);
        assertThat(c.encrypt("same-key")).isNotEqualTo(c.encrypt("same-key"));
    }

    @Test
    void tamperedCiphertextFails() {
        SecretCipher c = cipher(MASTER_KEY);
        String enc = c.encrypt("sk-live-abc123");
        byte[] raw = Base64.getDecoder().decode(enc);
        raw[raw.length - 1] ^= 0x01;
        assertThatThrownBy(() -> c.decrypt(Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void wrongMasterKeyFails() {
        String enc = cipher(MASTER_KEY).encrypt("sk-live-abc123");
        // 另一把全 0xFF 的主钥
        byte[] ff = new byte[32];
        java.util.Arrays.fill(ff, (byte) 0xFF);
        SecretCipher other = new SecretCipher(new LlmProperties(
                "https://api.example.com", "sk-x", "m1", Duration.ofSeconds(10), Duration.ofSeconds(60),
                Base64.getEncoder().encodeToString(ff)));
        assertThatThrownBy(() -> other.decrypt(enc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-key");
    }

    @Test
    void missingMasterKeyFailsFastOnUse() {
        SecretCipher c = cipher(null);
        assertThatThrownBy(() -> c.encrypt("sk-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-key 未配置");
    }

    @Test
    void badMasterKeyLengthFailsAtStartup() {
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }
}
