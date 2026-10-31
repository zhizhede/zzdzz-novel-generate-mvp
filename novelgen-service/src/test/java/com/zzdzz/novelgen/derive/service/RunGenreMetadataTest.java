package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.GenreProfileRegistry;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunGenreMetadataTest {

    private final GenreProfileRegistry registry = registry();

    @Test
    void createRunDefaultsToMystery() {
        assertEquals("MYSTERY", DeriveSampleService.normalizeGenre(null, registry));
    }

    @Test
    void createRunAcceptsExplicitGenre() {
        assertEquals("MYSTERY", DeriveSampleService.normalizeGenre("mystery", registry));
    }

    @Test
    void unknownGenreIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DeriveSampleService.normalizeGenre("UNKNOWN", registry));

        assertTrue(error.getMessage().contains("Unknown genre: UNKNOWN"));
    }

    @Test
    void runMetadataIsPersisted() {
        RunDO run = new RunDO();

        DeriveSampleService.applyRunMetadata(run, "MYSTERY");

        assertEquals("MYSTERY", run.getGenre());
        assertEquals(1, run.getStoryKernelVersion());
        assertEquals(1, run.getGenreContractVersion());
    }

    @Test
    void legacyRunWithoutMetadataReadsAsMystery() {
        RunDO run = new RunDO();

        assertNull(run.getGenre());
        assertEquals("MYSTERY", DeriveSampleService.normalizeGenre(run.getGenre(), registry));
    }

    private static GenreProfileRegistry registry() {
        GenreProfile<?> profile = new MysteryProfile();
        return new GenreProfileRegistry(List.of(profile));
    }
}
