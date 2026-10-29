package com.zzdzz.novelgen.derive.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证历史 LLM 输出回放 fixture 的统一 envelope 可被原样加载。 */
class ReplayFixtureLoaderTest {

    private static final String BASE_PATH = "derive-replay/";

    @Test
    void loadsRun60ObstacleFixture() {
        ReplayFixtureLoader.ReplayFixture fixture =
                ReplayFixtureLoader.load(BASE_PATH + "run60-route-obstacle.json");

        assertEquals("run60-route-obstacle", fixture.id());
        assertEquals("derive.route_narrative", fixture.contractId());
        assertEquals(2, fixture.schemaVersion());
        assertEquals("HISTORICAL", fixture.source().kind());
        assertEquals(60L, fixture.source().runId());
        assertEquals(3141L, fixture.source().callLogId());
        assertEquals("derive_plan", fixture.source().node());
        assertTrue(fixture.rawContent().contains("\"OBSTACLE\""));
    }

    @Test
    void loadsHistoricalFixtureWithSource() {
        ReplayFixtureLoader.ReplayFixture fixture =
                ReplayFixtureLoader.load(BASE_PATH + "run58-head-c-no-clue-keys.json");

        assertEquals("run58-head-c-no-clue-keys", fixture.id());
        assertEquals("derive.head_c", fixture.contractId());
        assertEquals(1, fixture.schemaVersion());
        assertEquals("HISTORICAL", fixture.source().kind());
        assertEquals(58L, fixture.source().runId());
        assertEquals(3087L, fixture.source().callLogId());
        assertEquals("derive_draft", fixture.source().node());
        assertEquals(md5(fixture.rawContent()), fixture.source().contentMd5());
    }

    @Test
    void loadsSyntheticFixtureWithNullSourceIds() {
        ReplayFixtureLoader.ReplayFixture fixture =
                ReplayFixtureLoader.load(BASE_PATH + "route-valid.json");

        assertEquals("route-v2-valid-synthetic", fixture.id());
        assertEquals("derive.route_narrative", fixture.contractId());
        assertEquals(2, fixture.schemaVersion());
        assertEquals("SYNTHETIC", fixture.source().kind());
        assertNull(fixture.source().runId());
        assertNull(fixture.source().callLogId());
        assertNull(fixture.source().node());
        assertEquals(md5(fixture.rawContent()), fixture.source().contentMd5());
        assertTrue(fixture.rawContent().contains("\"beatKey\""));
    }

    @ParameterizedTest
    @MethodSource("historicalFixtures")
    void loadsEveryHistoricalFixtureWithMatchingMetadata(
            String path,
            String fixtureId,
            long runId,
            long callLogId,
            String node,
            String rawContentMd5) {
        ReplayFixtureLoader.ReplayFixture fixture = ReplayFixtureLoader.load(BASE_PATH + path);

        assertEquals(fixtureId, fixture.id());
        assertEquals("HISTORICAL", fixture.source().kind());
        assertEquals(runId, fixture.source().runId());
        assertEquals(callLogId, fixture.source().callLogId());
        assertEquals(node, fixture.source().node());
        assertEquals(rawContentMd5, fixture.source().contentMd5());
        assertEquals(rawContentMd5, md5(fixture.rawContent()));
    }

    @Test
    void rejectsBlankResourcePath() {
        assertThrows(IllegalArgumentException.class, () -> ReplayFixtureLoader.load(null));
        assertThrows(IllegalArgumentException.class, () -> ReplayFixtureLoader.load(" "));
    }

    @Test
    void rejectsMissingResource() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplayFixtureLoader.load(BASE_PATH + "missing.json"));
    }

    @Test
    void rejectsBlankFixtureId() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ReplayFixtureLoader.load(
                        new ByteArrayInputStream(envelope(" ", "derive.route_narrative", 2, syntheticSource("{}"), "{}").getBytes(StandardCharsets.UTF_8)),
                        "inline-blank-fixture-id.json"));

        assertTrue(exception.getMessage().contains("fixtureId"));
    }

    @Test
    void rejectsNonPositiveSchemaVersion() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ReplayFixtureLoader.load(
                        new ByteArrayInputStream(envelope("inline-schema", "derive.route_narrative", 0, syntheticSource("{}"), "{}").getBytes(StandardCharsets.UTF_8)),
                        "inline-schema.json"));

        assertTrue(exception.getMessage().contains("schemaVersion"));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplayFixtureLoader.load(
                        new ByteArrayInputStream("not-json".getBytes(StandardCharsets.UTF_8)),
                        "inline-invalid.json"));
    }

    private static Stream<Arguments> historicalFixtures() {
        return Stream.of(
                Arguments.of(
                        "run44-c1-duplicate.json", "run44-c1-duplicate", 44L, 2149L,
                        "derive_plan", "b6dd51d2494d6b4bfceea206fc9f7d85"),
                Arguments.of(
                        "run58-head-c-no-clue-keys.json", "run58-head-c-no-clue-keys", 58L, 3087L,
                        "derive_draft", "6d57d08f88f5783d75629d0178ce03bf"),
                Arguments.of(
                        "run58-head-c-no-clue-keys-3088.json", "run58-head-c-no-clue-keys-3088",
                        58L, 3088L, "derive_draft", "a31a545919990421974536ad23c86f09"),
                Arguments.of(
                        "run58-head-c-no-clue-keys-3089.json", "run58-head-c-no-clue-keys-3089",
                        58L, 3089L, "derive_draft", "14566ea4fd1a24ba889414836be408be"),
                Arguments.of(
                        "run58-head-c-no-clue-keys-3107.json", "run58-head-c-no-clue-keys-3107",
                        58L, 3107L, "derive_draft", "307eb6280ca1907a94af91f88ee4c43a"),
                Arguments.of(
                        "run58-head-c-no-clue-keys-3108.json", "run58-head-c-no-clue-keys-3108",
                        58L, 3108L, "derive_draft", "b7d403546e8724b01eca71f6db050d42"),
                Arguments.of(
                        "run58-head-c-no-clue-keys-3109.json", "run58-head-c-no-clue-keys-3109",
                        58L, 3109L, "derive_draft", "047db681777d38e5bd6cffe938ec4813"),
                Arguments.of(
                        "run60-route-obstacle.json", "run60-route-obstacle", 60L, 3141L,
                        "derive_plan", "e0c83c7d3e4d03d9b0e4972fbfb9eb9b"),
                Arguments.of(
                        "run60-route-c1-duplicate.json", "run60-route-c1-duplicate", 60L, 3142L,
                        "derive_plan", "6c9d03d6306f23b58ce92ea379b5f133"),
                Arguments.of(
                        "run60-route-midpoint.json", "run60-route-midpoint", 60L, 3143L,
                        "derive_plan", "c1524ec2159dd3924a1b139abd08db6f"));
    }

    private static String syntheticSource(String rawContent) {
        return "{\"kind\":\"SYNTHETIC\",\"runId\":null,\"callLogId\":null,\"node\":null,"
                + "\"contentMd5\":\"" + md5(rawContent) + "\"}";
    }

    private static String envelope(
            String fixtureId, String contractId, int schemaVersion, String source, String rawContent) {
        return "{\"fixtureId\":" + quote(fixtureId)
                + ",\"contractId\":" + quote(contractId)
                + ",\"schemaVersion\":" + schemaVersion
                + ",\"source\":" + source
                + ",\"rawContent\":" + quote(rawContent) + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }
}