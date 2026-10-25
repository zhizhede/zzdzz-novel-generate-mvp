package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveStateLedgerServiceTest {

    @Test
    void knowledgeWrittenLaterCannotBeRequiredEarlier() {
        PlanContracts.BeatAssignment early = beat(1, 1, List.of("C1"),
                List.of("K-1"), List.of());
        PlanContracts.BeatAssignment late = beat(16, 2, List.of("C1"),
                List.of(), List.of("K-1"));

        DeriveStateLedgerService.StateLedger ledger = new DeriveStateLedgerService()
                .simulate(List.of(early, late), List.of(), List.of());

        assertEquals(1, ledger.errors().size());
        assertTrue(ledger.errors().get(0).contains("K-1"));
    }

    @Test
    void knowledgeWrittenEarlierIsAvailableLater() {
        PlanContracts.BeatAssignment early = beat(1, 1, List.of("C1"),
                List.of(), List.of("K-1"));
        PlanContracts.BeatAssignment late = beat(16, 2, List.of("C1"),
                List.of("K-1"), List.of());

        DeriveStateLedgerService.StateLedger ledger = new DeriveStateLedgerService()
                .simulate(List.of(early, late), List.of(), List.of());

        assertTrue(ledger.errors().isEmpty());
        assertTrue(ledger.knownByCharacter().get("C1").contains("K-1"));
    }

    @Test
    void payoffRoleMarksThreadPaid() {
        PlanContracts.BeatAssignment payoff = new PlanContracts.BeatAssignment(
                18, 1, PlanContracts.StoryLayer.LOCAL_CASE,
                PlanContracts.BeatRole.FINAL_CLOSURE, List.of("C1"),
                List.of("GM-CLUE-01"), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                List.of(), List.of(), Map.of(), Map.of());

        DeriveStateLedgerService.StateLedger ledger = new DeriveStateLedgerService()
                .simulate(List.of(payoff), List.of(), List.of());

        assertTrue(ledger.paidThreads().contains("GM-CLUE-01"));
    }

    private static PlanContracts.BeatAssignment beat(int chapter, int seq,
                                                     List<String> focus, List<String> requires,
                                                     List<String> writes) {
        return new PlanContracts.BeatAssignment(seq, chapter,
                PlanContracts.StoryLayer.LOCAL_CASE, PlanContracts.BeatRole.MAIN_ADVANCE,
                focus, List.of(), List.of(), "", PlanContracts.AppearanceMode.LIVE,
                requires, writes, Map.of(), Map.of());
    }
}