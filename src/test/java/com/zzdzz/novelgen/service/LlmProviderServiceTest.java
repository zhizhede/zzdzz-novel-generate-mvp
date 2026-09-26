package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.llm.LlmProperties;
import com.zzdzz.novelgen.llm.SecretCipher;
import com.zzdzz.novelgen.model.dto.LlmProviderDTO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 接入 CRUD：明文 key 只存密文、掩码回显不回明文、编辑留空保留原密文、单活约束。 */
class LlmProviderServiceTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private LlmProviderDataService providerData;
    private SecretCipher cipher;
    private LlmProviderService service;

    @BeforeEach
    void setUp() {
        providerData = mock(LlmProviderDataService.class);
        com.zzdzz.novelgen.llm.LlmProperties props = new com.zzdzz.novelgen.llm.LlmProperties(
                "https://yaml.example.com", "sk-yaml", "yaml-model",
                Duration.ofSeconds(5), Duration.ofSeconds(30), MASTER_KEY);
        cipher = new SecretCipher(props);
        service = new LlmProviderService(providerData, cipher, props);
    }

    @Test
    void createEncryptsKeyNeverStoresPlaintext() {
        when(providerData.findByName("主接入")).thenReturn(null);
        // 真 MyBatis-Plus save 会回填自增 ID——mock 照做
        when(providerData.save(any(LlmProviderDTO.class))).thenAnswer(inv -> {
            LlmProviderDTO p = inv.getArgument(0);
            p.setId(42L);
            return true;
        });
        service.create("主接入", "https://api.example.com", "sk-live-secret-9999", "model-x",
                null, null, true, "备注");

        ArgumentCaptor<LlmProviderDTO> captor = ArgumentCaptor.forClass(LlmProviderDTO.class);
        verify(providerData).save(captor.capture());
        LlmProviderDTO saved = captor.getValue();
        assertThat(saved.getApiKeyCipher()).doesNotContain("sk-live-secret");
        assertThat(cipher.decrypt(saved.getApiKeyCipher())).isEqualTo("sk-live-secret-9999");
        verify(providerData).disableAllOthers(saved.getId()); // 单活约束
    }

    @Test
    void listMasksKey() {
        LlmProviderDTO row = new LlmProviderDTO();
        row.setId(1L);
        row.setName("主接入");
        row.setBaseUrl("https://api.example.com");
        row.setApiKeyCipher(cipher.encrypt("sk-live-secret-9999"));
        row.setEnabled(true);
        when(providerData.listAll()).thenReturn(List.of(row));

        LlmProviderService.ProviderVO vo = service.list().get(0);
        assertThat(vo.keyMasked()).doesNotContain("sk-live-secret");
        assertThat(vo.keyMasked()).endsWith("9999").startsWith("sk-");
    }

    @Test
    void updateBlankKeyKeepsOldCipher() {
        LlmProviderDTO row = new LlmProviderDTO();
        row.setId(1L);
        row.setName("主接入");
        row.setBaseUrl("https://api.example.com");
        row.setApiKeyCipher(cipher.encrypt("sk-old-key-value"));
        row.setEnabled(true);
        when(providerData.findById(1L)).thenReturn(row);

        service.update(1L, null, null, "", null, null, null, null, null);

        assertThat(cipher.decrypt(row.getApiKeyCipher())).isEqualTo("sk-old-key-value");
        verify(providerData).updateById(row);
    }

    @Test
    void createRejectsBlankKeyAndBadBaseUrl() {
        assertThatThrownBy(() -> service.create("n", "https://api.example.com", " ", null, null, null, true, null))
                .isInstanceOf(BizException.class).hasMessageContaining("API key 必填");
        assertThatThrownBy(() -> service.create("n", "api.example.com", "sk-x", null, null, null, true, null))
                .isInstanceOf(BizException.class).hasMessageContaining("http");
        verify(providerData, never()).save(any());
    }

    @Test
    void createRejectsDuplicateName() {
        when(providerData.findByName("重名")).thenReturn(new LlmProviderDTO());
        assertThatThrownBy(() -> service.create("重名", "https://api.example.com", "sk-x", null, null, null, true, null))
                .isInstanceOf(BizException.class).hasMessageContaining("同名");
    }
}
