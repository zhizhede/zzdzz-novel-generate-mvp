package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.entity.CharacterStateDO;
import com.zzdzz.novelgen.service.data.CharacterStateDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人物状态账（V39）：投影自 digest 的 world_states（jsonb 键本来就是按人组织的），
 * 目的是「按人查、跨章比」；出稿后核对只做两条高精度规则（已死的人还在演 / 账上没有的名字）。
 * 夹具用的是书 56 第 9 章的真实快照（含「苏眠…已死」「孩子（沈砚之子，名沈砚）」这类真实脏数据）。
 */
class CharacterStateServiceTest {

    private static final long NOVEL_ID = 56L;
    private static final ObjectMapper M = new ObjectMapper();

    private CharacterStateDataService stateData;
    private WorldStateDataService worldStateData;
    private CharacterStateService service;

    @BeforeEach
    void setUp() {
        stateData = mock(CharacterStateDataService.class);
        worldStateData = mock(WorldStateDataService.class);
        EntityAliasService aliasService = mock(EntityAliasService.class);
        when(aliasService.index(anyLong())).thenReturn(java.util.Map.of());
        service = new CharacterStateService(stateData, worldStateData,
                mock(com.zzdzz.novelgen.service.data.ChapterDataService.class),
                mock(OutlineService.class), aliasService, M);
    }

    private static JsonNode fixture(String name) throws Exception {
        try (var in = CharacterStateServiceTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("夹具缺失: " + name).isNotNull();
            return M.readTree(in.readAllBytes());
        }
    }

    private static CharacterStateDO row(int chapterNo, String name, String location, String possessionsJson) {
        CharacterStateDO d = new CharacterStateDO();
        d.setNovelId(NOVEL_ID);
        d.setChapterNo(chapterNo);
        d.setName(name);
        d.setLocation(location);
        if (possessionsJson != null) {
            try {
                d.setPossessions(M.readTree(possessionsJson));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return d;
    }

    // ===== 投影 =====

    /** 真实快照投影：locations 的键 ∪ possessions 的键合并成行，物品按人挂上。 */
    @Test
    void projectsRealSnapshotIntoRows() throws Exception {
        JsonNode state = fixture("world_state_book56_ch9.json");

        List<CharacterStateDataService.Row> rows = CharacterStateService.rowsOf(9, state, java.util.Map.of());

        assertThat(rows).extracting(CharacterStateDataService.Row::name)
                // 带括号注释的名字被去括号（索引为空时也去）：「孩子（沈砚之子，名沈砚）」→「孩子」
                .containsExactlyInAnyOrder("沈砚", "苏眠", "陶渡", "孩子");
        var shenYan = rows.stream().filter(r -> r.name().equals("沈砚")).findFirst().orElseThrow();
        assertThat(shenYan.location()).isEqualTo("峡上索缆栈桥桩座旁");
        assertThat(shenYan.possessions()).contains("空白灯牌").contains("缆台");
        // 只在 locations 里出现的人在 possessions 侧为空
        var tao = rows.stream().filter(r -> r.name().equals("陶渡")).findFirst().orElseThrow();
        assertThat(tao.possessions()).isNull();
    }

    /** 空快照不造行（不该往账里写空名字）。 */
    @Test
    void skipsEmptyState() {
        assertThat(CharacterStateService.rowsOf(1, M.createObjectNode(), java.util.Map.of())).isEmpty();
        assertThat(CharacterStateService.rowsOf(1, null, java.util.Map.of())).isEmpty();
    }

    /** 整章账重写：先删后插（digest 重算不留第二份账）。 */
    @Test
    void projectReplacesWholeChapter() throws Exception {
        service.project(NOVEL_ID, 9, fixture("world_state_book56_ch9.json"));

        verify(stateData).replaceChapter(eq(NOVEL_ID), eq(9), any());
        ArgumentCaptor<List<CharacterStateDataService.Row>> cap = ArgumentCaptor.forClass(List.class);
        verify(stateData).replaceChapter(eq(NOVEL_ID), eq(9), cap.capture());
        assertThat(cap.getValue()).hasSize(4);
    }

    /** 表为空时查看会自动回填（存量书不该要求先手动建账）。 */
    @Test
    void ensureLedgerBackfillsOnFirstLookup() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of());
        when(worldStateData.listByNovel(eq(NOVEL_ID), anyInt())).thenReturn(List.of());

        service.byNovel(NOVEL_ID);

        verify(worldStateData).listByNovel(eq(NOVEL_ID), anyInt());
    }

    // ===== 核对规则（账内自洽） =====

    /** 死亡之后又被记成在场 → DEAD_STATE_DRIFT（带上死亡章与回写章）。 */
    @Test
    void auditFlagsStateDriftAfterDeath() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of(
                row(9, "苏眠", "峡上栈桥桩座边，已死，眼未合", null),
                row(10, "苏眠", "峡上石屋，正与沈砚说话", "[]"),
                row(10, "沈砚", "峡上石屋", null)));

        var findings = service.audit(NOVEL_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo("DEAD_STATE_DRIFT");
        assertThat(findings.get(0).name()).isEqualTo("苏眠");
        assertThat(findings.get(0).deathChapter()).isEqualTo(9);
        assertThat(findings.get(0).backChapter()).isEqualTo(10);
    }

    /** 死亡状态沿用到后续章节（真库口径：第 11 章仍写「已死，正文未写所在」）不算矛盾。 */
    @Test
    void auditIgnoresCarriedForwardDeathState() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of(
                row(9, "苏眠", "已死，眼未合", null),
                row(11, "苏眠", "已死，正文未写所在", null)));

        assertThat(service.audit(NOVEL_ID)).isEmpty();
    }

    /** 死亡之后的章什么都没记（无位置无物品）不算矛盾。 */
    @Test
    void auditIgnoresEmptyRowsAfterDeath() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of(
                row(9, "苏眠", "已死", null),
                row(10, "苏眠", null, null)));

        assertThat(service.audit(NOVEL_ID)).isEmpty();
    }

    /** 只记了随身物品也算「又被当成在场」（物品归属继承自活人语义）。 */
    @Test
    void auditFlagsItemsAfterDeath() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of(
                row(9, "苏眠", "已死", null),
                row(12, "苏眠", null, "[\"淡青纹服\"]")));

        var findings = service.audit(NOVEL_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).detail()).contains("只记了随身物品");
    }

    /** 同一个角色的漂移只报首次，不刷成一串。 */
    @Test
    void auditReportsEachCharacterOnce() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of(
                row(9, "苏眠", "已死", null),
                row(10, "苏眠", "峡上石屋", null),
                row(12, "苏眠", "灯城", null)));

        assertThat(service.audit(NOVEL_ID)).hasSize(1);
    }

    /** 没死过的角色一路正常记账 → 不报。 */
    @Test
    void auditIgnoresLivingCharacters() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of(
                row(1, "沈砚", "热流峡上石屋", null),
                row(2, "沈砚", "峡上索缆栈桥", null)));

        assertThat(service.audit(NOVEL_ID)).isEmpty();
    }

    /** 死亡标记判据（纯函数）：殡葬字眼也算离场（真库：遗体被移上葬台）。 */
    @Test
    void deathMarkDetection() {
        assertThat(CharacterStateService.isDeathMarked("峡上栈桥桩座边，已死，眼未合")).isTrue();
        assertThat(CharacterStateService.isDeathMarked("苏眠尸身未合眼")).isTrue();
        assertThat(CharacterStateService.isDeathMarked("阵亡于北隘门外")).isTrue();
        assertThat(CharacterStateService.isDeathMarked("峡上脉流葬台，横索灰布间")).isTrue();
        assertThat(CharacterStateService.isDeathMarked("热流峡上石屋")).isFalse();
        assertThat(CharacterStateService.isDeathMarked(null)).isFalse();
    }

    /** 遗体被移上葬台（真库第 10 章原句）→ 仍是离场状态，不该判成「死后复活」。 */
    @Test
    void auditTreatsBurialPlatformAsStillDead() {
        when(stateData.listByNovel(NOVEL_ID)).thenReturn(List.of(
                row(9, "苏眠", "峡上栈桥桩座边，已死，眼未合", null),
                row(10, "苏眠", "峡上脉流葬台，横索灰布间", null)));

        assertThat(service.audit(NOVEL_ID)).isEmpty();
    }
}
