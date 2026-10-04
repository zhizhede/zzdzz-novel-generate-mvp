package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 素材卡改名撞活卡基线（2026-09-30 排查同族 bug 时发现）：唯一索引
 * uq_material_cards_novel_kind_name 只管活行的 (novel_id, kind, name)，create 一直查重、
 * update 原先没查——把卡名改成同类型下另一张活卡的名字会直接撞唯一键，界面只看到数据库键冲突。
 */
class MaterialCardServiceTest {

    private MaterialCardDataService cardDAO;
    private MaterialCardService service;

    @BeforeEach
    void setUp() {
        cardDAO = mock(MaterialCardDataService.class);
        service = new MaterialCardService(cardDAO, mock(EntityAliasService.class),
                mock(TuningService.class), mock(PromptTemplateService.class));
    }

    private MaterialCardDO card() {
        MaterialCardDO d = new MaterialCardDO();
        d.setId(7L);
        d.setNovelId(3L);
        d.setKind(MaterialCardDO.KIND_CHARACTER);
        d.setName("旧名");
        d.setStatus("active");
        return d;
    }

    @Test
    void renameOntoAnotherLiveCardIsRefusedBeforeSql() {
        when(cardDAO.findById(7L)).thenReturn(card());
        when(cardDAO.existsOther(3L, MaterialCardDO.KIND_CHARACTER, "已占用", 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, "已占用", null, "摘要", null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在同名卡");
        verify(cardDAO, never()).update(anyLong(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void renameToFreeNameGoesThrough() {
        when(cardDAO.findById(7L)).thenReturn(card());
        when(cardDAO.existsOther(3L, MaterialCardDO.KIND_CHARACTER, "新名", 7L)).thenReturn(false);

        service.update(7L, "新名", List.of(), "摘要", "正文", true, "active", 2);

        verify(cardDAO).update(eq(7L), eq("新名"), any(), eq("摘要"), eq("正文"), eq(true), eq("active"), eq(2));
    }

    @Test
    void createStillChecksAndNamesTheConflict() {
        when(cardDAO.exists(3L, MaterialCardDO.KIND_CHARACTER, "已占用")).thenReturn(true);

        assertThatThrownBy(() -> service.create(3L, MaterialCardDO.KIND_CHARACTER, "已占用",
                null, null, null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在同名卡");
        verify(cardDAO, never()).insert(anyLong(), anyString(), anyString(), any(), any(), any(),
                anyBoolean(), anyString(), anyInt());
    }
}
