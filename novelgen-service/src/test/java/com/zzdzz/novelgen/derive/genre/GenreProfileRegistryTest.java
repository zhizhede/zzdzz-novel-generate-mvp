package com.zzdzz.novelgen.derive.genre;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenreProfileRegistryTest {

    @Test
    void registryKnowsMystery() {
        MysteryProfile profile = new MysteryProfile();
        GenreProfileRegistry registry = new GenreProfileRegistry(List.of(profile));

        assertEquals("MYSTERY", registry.require("MYSTERY").genre());
    }

    @Test
    void registryRejectsUnknownGenre() {
        GenreProfileRegistry registry = new GenreProfileRegistry(List.of(new MysteryProfile()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.require("UNKNOWN"));

        assertTrue(error.getMessage().contains("Unknown genre: UNKNOWN"));
    }

    @Test
    void registryReturnsSingleMysteryInstance() {
        MysteryProfile profile = new MysteryProfile();
        GenreProfileRegistry registry = new GenreProfileRegistry(List.of(profile));

        assertSame(profile, registry.require("mystery"));
    }
}
