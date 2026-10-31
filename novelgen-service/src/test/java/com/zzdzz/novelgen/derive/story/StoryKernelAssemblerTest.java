package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryKernelAssemblerTest {

    @Test
    void assemblerKeepsPayloadEvidenceCharactersAndThreads() {
        StoryObligation obligation = new StoryObligation("CH2:PLANT_THREAD:GM-CLUE-01", 2,
                "PLANT_THREAD", "GM-CLUE-01", true, false, "HARD", "MYSTERY",
                Map.of("clueKey", "GM-CLUE-01", "phase", "PLANT"));
        StoryObligationEvidence evidence = new StoryObligationEvidence(
                obligation.obligationKey(), 2, "BEAT", "EV1", 1, List.of("C1"),
                List.of("GM-CLUE-01"), "纸屑", "BATCH", 1.0);
        StoryEvidenceIndex index = StoryEvidenceIndex.builder()
                .addObligationEvidence(evidence)
                .build();
        StoryThread thread = new StoryThread("GM-CLUE-01", "CLUE", "纸屑", 2, "OPEN",
                "C1", 4, Map.of());

        StoryKernel kernel = new StoryKernelAssembler().assemble(
                run(), contract(), List.of(new ChapterScheduleEntry(2, 1, false,
                        List.of("GM-CLUE-01"), List.of())),
                List.of(), List.of(), List.of(thread), List.of(obligation), index);

        assertEquals("PLANT", kernel.obligations().get(0).payload().get("phase"));
        assertTrue(kernel.evidenceIndex().hasEvidence(obligation.obligationKey()));
        assertEquals("林默", kernel.entities().stream()
                .filter(entity -> "C1".equals(entity.key())).findFirst().orElseThrow().name());
        assertEquals("GM-CLUE-01", kernel.threads().get(0).threadKey());
    }

    @Test
    void assemblerRejectsDuplicateEntityKey() {
        StoryContract duplicate = new StoryContract(1L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C1", "苏晚", "ACCESS", "女", "档案员")),
                null, List.of(), List.of(), List.of(), List.of(), scope());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StoryKernelAssembler().assemble(run(), duplicate, List.of(),
                        List.of(), List.of(), List.of(), List.of(), StoryEvidenceIndex.builder().build()));

        assertTrue(error.getMessage().contains("重复"));
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(1L);
        run.setGenre("MYSTERY");
        return run;
    }

    private static StoryContract contract() {
        return new StoryContract(1L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                null, List.of(), List.of(), List.of(), List.of(), scope());
    }

    private static PlanContracts.ScopeContract scope() {
        return new PlanContracts.ScopeContract(PlanContracts.ScopeDecision.SINGLE_VOLUME,
                1, 4, 2000, List.of("测试"));
    }
}
