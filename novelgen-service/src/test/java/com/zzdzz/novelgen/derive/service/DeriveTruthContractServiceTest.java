package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveTruthContractServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void conflictingTruthSourcesAreReported() throws Exception {
        var spine = M.readTree("{\"finalTruth\":\"A\",\"finalChoice\":\"X\",\"globalThreads\":[{\"threadKey\":\"GM-1\"}]}");
        var card = M.readTree("{\"truth\":{\"finalTruth\":\"B\",\"finalChoice\":\"Y\",\"evidenceThreads\":[\"GM-1\"]}}");
        var volumes = M.readTree("[]");

        var result = new DeriveTruthContractService().resolve(43, spine, card, volumes);

        assertFalse(result.conflicts().isEmpty());
        assertTrue(result.contract().truthId().startsWith("T-"));
        assertTrue(result.contract().evidenceThreads().contains("GM-1"));
    }

    @Test
    void matchingTruthSourcesHaveNoConflict() throws Exception {
        var spine = M.readTree("{\"finalTruth\":\"A\",\"finalChoice\":\"X\"}");
        var card = M.readTree("{\"truth\":{\"finalTruth\":\"A\",\"finalChoice\":\"X\"}}");

        var result = new DeriveTruthContractService().resolve(43, spine, card, M.readTree("[]"));

        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void rootSolutionCardBecomesCanonicalTruth() throws Exception {
        var spine = M.readTree("{\"finalTruth\":\"珍品骗局：少年被囚禁\"}");
        var card = M.readTree("{\"solution\":{\"culprit\":\"苏晚\",\"motive\":\"嫉妒\",\"method\":\"伪造去向\"},\"evidenceThreads\":[\"GM-1\"]}");
        var result = new DeriveTruthContractService().resolve(43, spine, card, M.readTree("[]"));

        assertTrue(result.contract().finalTruth().contains("苏晚"));
        assertFalse(result.conflicts().isEmpty());
        assertTrue(result.contract().evidenceThreads().contains("GM-1"));
    }

    @Test
    void narrativeVolumeTextDoesNotPolluteEvidenceThreads() throws Exception {
        var spine = M.readTree("{\"finalTruth\":\"A\",\"finalChoice\":\"X\",\"globalThreads\":[{\"threadKey\":\"GM-1\"}]}");
        var card = M.readTree("{\"truth\":{\"finalTruth\":\"A\",\"finalChoice\":\"X\"}}");
        var volumes = M.readTree("[{\"arc\":\"这是一段很长的卷纲叙述\",\"requiredThreads\":[\"GM-2\"]}]");

        var result = new DeriveTruthContractService().resolve(43, spine, card, volumes);

        assertTrue(result.contract().evidenceThreads().contains("GM-1"));
        assertTrue(result.contract().evidenceThreads().contains("GM-2"));
        assertTrue(result.contract().evidenceThreads().stream().noneMatch(s -> s.contains("卷纲叙述")));
    }}