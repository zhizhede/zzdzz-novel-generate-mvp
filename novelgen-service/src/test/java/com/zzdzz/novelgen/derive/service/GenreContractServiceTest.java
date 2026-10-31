package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.GenreProfileRegistry;
import com.zzdzz.novelgen.derive.genre.MysteryProfile;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenreContractServiceTest {

    private final GenreProfileRegistry registry = new GenreProfileRegistry(
            List.of(new MysteryProfile()));

    @Test
    void contractUsesGenreProfileParser() {
        GenreProfile profile = DeriveContractService.profileForRun(run("MYSTERY"), registry);

        assertEquals("MYSTERY", profile.genre());
    }

    @Test
    void legacyMysteryContractStillParses() {
        GenreProfile profile = DeriveContractService.profileForRun(run(null), registry);

        assertEquals("MYSTERY", profile.genre());
    }

    @Test
    void unknownGenreFailsBeforeLlmCall() {
        BizException error = assertThrows(BizException.class,
                () -> DeriveContractService.profileForRun(run("UNKNOWN"), registry));

        assertTrue(error.getMessage().contains("Unknown genre: UNKNOWN"));
    }

    private static RunDO run(String genre) {
        RunDO run = new RunDO();
        run.setGenre(genre);
        return run;
    }
}
