package com.zzdzz.novelgen.derive.contract;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 从测试 classpath 读取历史 LLM 输出回放样本。 */
public final class ReplayFixtureLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ReplayFixtureLoader() {
    }

    /**
     * 读取统一 envelope 并返回未经修复的模型原文。
     *
     * @param resourcePath 测试 classpath 资源路径
     * @return 回放样本
     */
    public static ReplayFixture load(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("Replay fixture resource path must not be blank");
        }
        try (InputStream input = ReplayFixtureLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Replay fixture not found: " + resourcePath);
            }
            return load(input, resourcePath);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Cannot read replay fixture: " + resourcePath, exception);
        }
    }

    static ReplayFixture load(InputStream input, String resourcePath) {
        try {
            Envelope envelope = OBJECT_MAPPER.readValue(input, Envelope.class);
            requireText(envelope.fixtureId(), "fixtureId", resourcePath);
            requireText(envelope.contractId(), "contractId", resourcePath);
            requireText(envelope.rawContent(), "rawContent", resourcePath);
            if (envelope.schemaVersion() <= 0) {
                throw new IllegalArgumentException(
                        "Replay fixture schemaVersion must be positive: " + resourcePath);
            }
            ReplaySource source = validateSource(envelope.source(), envelope.rawContent(), resourcePath);
            return new ReplayFixture(
                    envelope.fixtureId(),
                    envelope.contractId(),
                    envelope.schemaVersion(),
                    source,
                    envelope.rawContent());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Cannot read replay fixture: " + resourcePath, exception);
        }
    }

    private static ReplaySource validateSource(
            ReplaySource source, String rawContent, String resourcePath) {
        if (source == null) {
            throw new IllegalArgumentException("Replay fixture source must not be null: " + resourcePath);
        }
        requireText(source.kind(), "source.kind", resourcePath);
        requireText(source.contentMd5(), "source.contentMd5", resourcePath);
        String kind = source.kind().trim();
        if (!"HISTORICAL".equals(kind) && !"SYNTHETIC".equals(kind)) {
            throw new IllegalArgumentException(
                    "Replay fixture source.kind must be HISTORICAL or SYNTHETIC: " + resourcePath);
        }
        if ("HISTORICAL".equals(kind)) {
            if (source.runId() == null || source.callLogId() == null || source.node() == null
                    || source.node().isBlank()) {
                throw new IllegalArgumentException(
                        "Historical replay source requires runId, callLogId and node: " + resourcePath);
            }
        } else if (source.runId() != null || source.callLogId() != null || source.node() != null) {
            throw new IllegalArgumentException(
                    "Synthetic replay source must have null runId, callLogId and node: " + resourcePath);
        }
        if (!source.contentMd5().equals(md5(rawContent))) {
            throw new IllegalArgumentException(
                    "Replay fixture source.contentMd5 does not match rawContent: " + resourcePath);
        }
        return source;
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

    private static void requireText(String value, String field, String resourcePath) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Replay fixture " + field + " must not be blank: " + resourcePath);
        }
    }

    /** 统一回放样本；rawContent 与模型 content 逐字一致。 */
    public record ReplayFixture(
            String id,
            String contractId,
            int schemaVersion,
            ReplaySource source,
            String rawContent) {
    }

    /** 回放来源证明；合成样例的运行时标识必须为空。 */
    public record ReplaySource(
            String kind,
            Long runId,
            Long callLogId,
            String node,
            String contentMd5) {
    }

    private record Envelope(
            String fixtureId,
            String contractId,
            int schemaVersion,
            ReplaySource source,
            String rawContent) {
    }
}