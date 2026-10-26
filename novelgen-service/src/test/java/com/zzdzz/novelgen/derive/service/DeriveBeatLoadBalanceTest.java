package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P6 Task3：拍负载与章容量归一（路由层 + 具体稿终局层）。 */
class DeriveBeatLoadBalanceTest {

    @Test
    void overloadedChapterBeatsAreRebalanced() {
        var plan = routePlan(assignment(1, 1, PlanContracts.BeatRole.HOOK),
                assignment(2, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(3, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(4, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(5, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(6, 1, PlanContracts.BeatRole.CLUE_ECHO),
                assignment(7, 1, PlanContracts.BeatRole.CLUE_ECHO));

        var balanced = DeriveBeatRouteService.rebalanceRouteLoad(plan, context());

        // 总拍 8（7 普通 + 1 synthetic），上限 max(2, ceil(8*1.5/2))=6；
        // 超载章只搬到不超上限为止，不要求完全均分。
        long chapter1 = balanced.assignments().stream()
                .filter(a -> a.chapterNo() == 1).count();
        long chapter2 = balanced.assignments().stream()
                .filter(a -> a.chapterNo() == 2).count();
        assertTrue(chapter1 <= 6, "超载章必须降到上限内，实际=" + chapter1);
        assertTrue(chapter2 >= 1, "欠载章必须接手普通推进拍，实际=" + chapter2);
    }

    @Test
    void requiredClosureBeatsAreNeverMoved() {
        var plan = routePlan(assignment(1, 1, PlanContracts.BeatRole.HOOK),
                assignment(2, 1, PlanContracts.BeatRole.LOCAL_CLOSURE),
                assignment(3, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(4, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(5, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(6, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(7, 1, PlanContracts.BeatRole.MAIN_ADVANCE));

        var balanced = DeriveBeatRouteService.rebalanceRouteLoad(plan, context());

        assertTrue(balanced.assignments().stream()
                        .anyMatch(a -> a.seq() == 1 && a.chapterNo() == 1),
                "HOOK 必须留原章");
        assertTrue(balanced.assignments().stream()
                        .anyMatch(a -> a.seq() == 2 && a.chapterNo() == 1),
                "LOCAL_CLOSURE 必须留原章");
    }

    @Test
    void rebalanceKeepsSeqAndChapterConsistency() {
        var plan = routePlan(assignment(1, 1, PlanContracts.BeatRole.HOOK),
                assignment(2, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(3, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(4, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(5, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(6, 1, PlanContracts.BeatRole.MAIN_ADVANCE),
                assignment(7, 1, PlanContracts.BeatRole.CLUE_PLANT));

        var balanced = DeriveBeatRouteService.rebalanceRouteLoad(plan, context());

        var seqs = balanced.assignments().stream()
                .map(PlanContracts.BeatAssignment::seq).sorted().toList();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), seqs, "seq 集合不得变化");
        assertTrue(balanced.assignments().stream().allMatch(
                        a -> a.chapterNo() == 1 || a.chapterNo() == 2),
                "章号必须仍在卷范围内");
        assertEquals(2, balanced.chapters().size());
    }

    @Test
    void terminalRebalanceKeepsSeqAndSyncsRouteContract() {
        ChapterDO ch1 = chapter(1);
        ChapterDO ch2 = chapter(2);
        List<BeatInstanceDO> beats = new ArrayList<>();
        BeatInstanceDO hook = beat(1, 1, "HOOK");
        beats.add(hook);
        for (int seq = 2; seq <= 8; seq++) {
            beats.add(beat(seq, 1, "MAIN_ADVANCE"));
        }
        beats.add(beat(9, 2, "LOCAL_CLOSURE"));
        List<BeatInstanceDO> changed = new ArrayList<>();

        DeriveDraftService.rebalanceBeatLoad(List.of(ch1, ch2), beats, changed);

        var seqs = beats.stream().map(BeatInstanceDO::getSeq).sorted().toList();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), seqs, "终局搬移不得改 seq");
        assertTrue(beats.stream().allMatch(b ->
                        b.getChapterNo() == 1 || b.getChapterNo() == 2),
                "终局搬移章号必须仍在卷范围内");
        assertTrue(changed.stream().allMatch(b -> b.getRouteContract() == null
                        || !b.getRouteContract().contains("\"chapterNo\":1")
                        || b.getChapterNo() == 1),
                "被搬动拍的 route_contract.chapterNo 必须与章行同步");
        assertTrue(beats.stream().anyMatch(b -> b.getSeq() == 1 && b.getChapterNo() == 1),
                "每章至少保留 1 拍且保护拍不动");
    }

    private static PlanContracts.RoutePlan routePlan(PlanContracts.BeatAssignment... assigns) {
        var hook = new PlanContracts.ChapterSlotContract(
                1, "HOOK", "开篇", "启动案件", "C1介入", "关系建立",
                List.of(PlanContracts.BeatRole.HOOK), List.of(), Map.of(), Map.of());
        var middle = new PlanContracts.ChapterSlotContract(
                2, "MIDPOINT", "中段", "线索升级", "C2暴露", "关系破裂",
                List.of(PlanContracts.BeatRole.HIGHLIGHT), List.of(), Map.of(), Map.of());
        var synthetic = new PlanContracts.SyntheticAssignment(
                2, PlanContracts.StoryLayer.LOCAL_CASE, PlanContracts.BeatRole.HIGHLIGHT,
                "抉择", "补齐高光", List.of("C2"), List.of(),
                PlanContracts.AppearanceMode.LIVE);
        return new PlanContracts.RoutePlan(
                1, List.of(hook, middle), new ArrayList<>(List.of(assigns)),
                List.of(synthetic), List.of());
    }

    private static PlanContracts.BeatAssignment assignment(
            int seq, int chapterNo, PlanContracts.BeatRole role) {
        return new PlanContracts.BeatAssignment(
                seq, chapterNo, PlanContracts.StoryLayer.LOCAL_CASE, role,
                List.of("C1"), List.of(), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                List.of(), List.of(), Map.of(), Map.of());
    }

    private static DeriveBeatRouteService.RouteContext context() {
        PlanContracts.VolumeContract volume = new PlanContracts.VolumeContract(
                1, 1, 2, "失踪案", "谁改了名单", "自导自演", "名单被替换",
                "增量", List.of("C1"), List.of(), List.of(), List.of(), "闭合", "",
                "LOCAL");
        return new DeriveBeatRouteService.RouteContext(
                volume, List.of(), java.util.Set.of("C1"), java.util.Set.of(),
                100, Map.of(), Map.of());
    }

    private static ChapterDO chapter(int no) {
        ChapterDO chapter = new ChapterDO();
        chapter.setChapterNo(no);
        chapter.setTitle("第" + no + "章");
        return chapter;
    }

    private static BeatInstanceDO beat(int seq, int chapterNo, String role) {
        BeatInstanceDO beat = new BeatInstanceDO();
        beat.setSeq(seq);
        beat.setChapterNo(chapterNo);
        beat.setBeatRole(role);
        beat.setStatus("PLANNED");
        beat.setRouteContract("{\"chapterNo\":" + chapterNo + ",\"seq\":" + seq + "}");
        return beat;
    }
}
