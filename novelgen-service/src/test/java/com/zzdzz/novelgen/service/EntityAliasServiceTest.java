package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.model.entity.EntityAliasDO;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import com.zzdzz.novelgen.service.data.EntityAliasDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 别名反查索引（V40）：把 material_cards.aliases 那个「只能整卡加载再内存比」的 jsonb 数组
 * 做成可反查的表；真实消费者是**名字归一化**——人物账的名字来自模型输出（别名、带括号注释），
 * 不归一就与卡名对不上，任何按名字的比对都会漏。
 */
class EntityAliasServiceTest {

    private static final long NOVEL_ID = 56L;

    private EntityAliasDataService aliasData;
    private MaterialCardDataService cardDAO;
    private EntityAliasService service;

    @BeforeEach
    void setUp() {
        aliasData = mock(EntityAliasDataService.class);
        cardDAO = mock(MaterialCardDataService.class);
        service = new EntityAliasService(aliasData, cardDAO);
    }

    private static MaterialCardDO card(long id, String name, List<String> aliases) {
        MaterialCardDO c = new MaterialCardDO();
        c.setId(id);
        c.setNovelId(NOVEL_ID);
        c.setKind(MaterialCardDO.KIND_CHARACTER);
        c.setName(name);
        c.setAliases(aliases);
        return c;
    }

    /** 重建：卡名自己也是一行（is_primary），别名各一行；卡名与别名相同的去重。 */
    @Test
    void rebuildWritesCardNameAndAliases() {
        when(cardDAO.listByNovel(NOVEL_ID, null)).thenReturn(List.of(
                card(11L, "陆朴", List.of("老陆", "陆朴")),
                card(12L, "陶渡", List.of())));

        int n = service.rebuild(NOVEL_ID);

        assertThat(n).isEqualTo(3); // 陆朴 / 老陆 / 陶渡（"陆朴" 与卡名重复被去掉）
        ArgumentCaptor<List<EntityAliasDataService.Row>> cap = ArgumentCaptor.forClass(List.class);
        verify(aliasData).rebuild(org.mockito.ArgumentMatchers.eq(NOVEL_ID), cap.capture());
        assertThat(cap.getValue()).extracting(EntityAliasDataService.Row::alias)
                .containsExactlyInAnyOrder("陆朴", "老陆", "陶渡");
        assertThat(cap.getValue().stream().filter(r -> r.alias().equals("陆朴")).findFirst().orElseThrow().primary())
                .isTrue();
    }

    /** 过短的别名不建索引（「灯」「门」这类会误命中）；空白别名跳过。 */
    @Test
    void rebuildSkipsTooShortOrBlankAliases() {
        when(cardDAO.listByNovel(NOVEL_ID, null)).thenReturn(List.of(
                card(11L, "陆朴", java.util.Arrays.asList("陆", " ", null, "老陆"))));

        service.rebuild(NOVEL_ID);

        ArgumentCaptor<List<EntityAliasDataService.Row>> cap = ArgumentCaptor.forClass(List.class);
        verify(aliasData).rebuild(org.mockito.ArgumentMatchers.eq(NOVEL_ID), cap.capture());
        assertThat(cap.getValue()).extracting(EntityAliasDataService.Row::alias)
                .containsExactlyInAnyOrder("陆朴", "老陆");
    }

    /** 同书内两张卡抢同一个别名：先到先得并留痕，不能让唯一索引把整次重建打挂。 */
    @Test
    void rebuildDeduplicatesAliasConflictInsteadOfFailing() {
        when(cardDAO.listByNovel(NOVEL_ID, null)).thenReturn(List.of(
                card(11L, "陆朴", List.of("老陆")),
                card(13L, "陆平", List.of("老陆"))));

        service.rebuild(NOVEL_ID);

        ArgumentCaptor<List<EntityAliasDataService.Row>> cap = ArgumentCaptor.forClass(List.class);
        verify(aliasData).rebuild(org.mockito.ArgumentMatchers.eq(NOVEL_ID), cap.capture());
        List<EntityAliasDataService.Row> rows = cap.getValue();
        assertThat(rows.stream().filter(r -> r.alias().equals("老陆"))).hasSize(1);
        assertThat(rows.stream().filter(r -> r.alias().equals("老陆")).findFirst().orElseThrow().cardName())
                .isEqualTo("陆朴");
    }

    /** 括号注释判据：「孩子（沈砚之子，名沈砚）」→「孩子」；没有注释或注释在中间则原样。 */
    @Test
    void stripTrailingParenOnlyCutsTrailingNote() {
        assertThat(EntityAliasService.stripTrailingParen("孩子（沈砚之子，名沈砚）")).isEqualTo("孩子");
        assertThat(EntityAliasService.stripTrailingParen("孩子(沈砚之子)")).isEqualTo("孩子");
        assertThat(EntityAliasService.stripTrailingParen("沈砚")).isEqualTo("沈砚");
        assertThat(EntityAliasService.stripTrailingParen("（前括号）")).isEqualTo("（前括号）");
        assertThat(EntityAliasService.stripTrailingParen("灯城（旧）门")).isEqualTo("灯城（旧）门");
    }

    /** 名字归一化：别名与带注释的名字都归到卡名；索引里没有的名字只去括号。 */
    @Test
    void canonicalNameResolvesAliasAndParenthetical() {
        EntityAliasDO laoLu = new EntityAliasDO();
        laoLu.setAlias("老陆");
        laoLu.setCardName("陆朴");
        when(aliasData.listByNovel(NOVEL_ID)).thenReturn(List.of(laoLu));

        assertThat(service.canonicalName(NOVEL_ID, "老陆")).isEqualTo("陆朴");
        assertThat(service.canonicalName(NOVEL_ID, "老陆 ")).isEqualTo("陆朴");
        assertThat(service.canonicalName(NOVEL_ID, "孩子（沈砚之子，名沈砚）")).isEqualTo("孩子");
        assertThat(service.canonicalName(NOVEL_ID, "苏眠")).isEqualTo("苏眠");
    }

    /** 索引为空且本书无卡：不做无谓重建（否则每次归一化都白跑一遍）。 */
    @Test
    void doesNotRebuildWhenBookHasNoCards() {
        when(aliasData.listByNovel(NOVEL_ID)).thenReturn(List.of());
        when(cardDAO.hasCards(NOVEL_ID)).thenReturn(false);

        assertThat(service.index(NOVEL_ID)).isEmpty();

        verify(cardDAO, org.mockito.Mockito.never()).listByNovel(anyLong(), any());
    }
}
