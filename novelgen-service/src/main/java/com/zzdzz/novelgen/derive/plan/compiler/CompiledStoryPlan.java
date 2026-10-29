package com.zzdzz.novelgen.derive.plan.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 已编译但尚未准入的不可变计划快照图。 */
public record CompiledStoryPlan(
        long runId,
        int contractVersion,
        String contractHash,
        PlanContracts.ScopeContract scope,
        List<ChapterScheduleEntry> schedule,
        List<PlanContracts.VolumeContract> volumes,
        List<PlanContracts.CharacterContract> characters,
        List<PlanGraphRecords.PlannedChapter> chapters,
        List<PlanGraphRecords.PlannedBeat> beats) {

    private static final JsonMapper CODEC = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public CompiledStoryPlan {
        if (runId <= 0 || contractVersion <= 0) {
            throw new IllegalArgumentException("runId/contractVersion must be positive");
        }
        Objects.requireNonNull(scope, "scope");
        schedule = copy(schedule);
        volumes = copy(volumes);
        characters = copy(characters);
        chapters = copy(chapters);
        beats = copy(beats);
        requireContinuous(schedule, ChapterScheduleEntry::chapterNo, "schedule.chapterNo");
        requireContinuous(chapters, PlanGraphRecords.PlannedChapter::chapterNo, "chapters.chapterNo");
        requireContinuous(beats, PlanGraphRecords.PlannedBeat::seq, "beats.seq");
        requireUniqueCharacterKeys(characters);
        if (contractHash == null || contractHash.isBlank()) {
            throw new IllegalArgumentException("contractHash must not be blank");
        }
    }

    /** 稳定 JSON：对象键排序，列表保持计划语义顺序。 */
    public String canonicalJson() {
        try {
            return CODEC.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("计划快照序列化失败", e);
        }
    }

    /** 从稳定 JSON 反序列化计划快照。 */
    public static CompiledStoryPlan fromJson(String json) {
        try {
            return CODEC.readValue(json, CompiledStoryPlan.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("计划快照 JSON 无效", e);
        }
    }

    /** 计划内容的 SHA-256；同一计划必须得到同一 hash。 */
    public String canonicalHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> void requireContinuous(List<T> values,
                                              java.util.function.ToIntFunction<T> number,
                                              String label) {
        for (int index = 0; index < values.size(); index++) {
            if (number.applyAsInt(values.get(index)) != index + 1) {
                throw new IllegalArgumentException(label + " must be continuous from 1");
            }
        }
    }

    private static void requireUniqueCharacterKeys(List<PlanContracts.CharacterContract> values) {
        Set<String> keys = new HashSet<>();
        for (PlanContracts.CharacterContract character : values) {
            if (!keys.add(character.characterKey())) {
                throw new IllegalArgumentException("duplicate characterKey: " + character.characterKey());
            }
        }
    }
}