package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveFactMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveRepairLogMapper;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveFactDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveRepairLogDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P1 FactLedger：从已落库路由重建事实账，并记录确定性修复审计。
 *
 * <p>事实账不替代 route_contract，而是把自由文本知识来源集中成可查询、可回放的账本。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveFactService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BeatInstanceMapper beatInstanceMapper;
    private final DeriveFactMapper factMapper;
    private final DeriveRepairLogMapper repairLogMapper;

    /** 按 run/novel 重建事实账；同一 factKey 幂等更新。 */
    public void rebuild(long runId, long novelId) {
        List<BeatInstanceDO> beats = new ArrayList<>(beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>()
                        .eq(BeatInstanceDO::getRunId, runId)
                        .eq(BeatInstanceDO::getStatus, "PLANNED")));
        beats.sort(java.util.Comparator
                .comparingInt((BeatInstanceDO beat) ->
                        beat.getChapterNo() == null ? Integer.MAX_VALUE : beat.getChapterNo())
                .thenComparingInt(BeatInstanceDO::getSeq));
        Map<String, FactDraft> drafts = new HashMap<>();
        for (BeatInstanceDO beat : beats) {
            JsonNode route = readTreeSafe(beat.getRouteContract());
            List<String> focus = strings(route.path("focusCharacters"));
            for (JsonNode write : route.path("writesKnowledge")) {
                FactDraft draft = drafts.computeIfAbsent(
                        factKey(write.asText("")), key -> new FactDraft(write.asText("")));
                draft.writers.addAll(focus);
                if (beat.getChapterNo() != null) {
                    draft.plantedChapter = minChapter(draft.plantedChapter, beat.getChapterNo());
                }
            }
            for (JsonNode require : route.path("requiresKnowledge")) {
                FactDraft draft = drafts.computeIfAbsent(
                        factKey(require.asText("")), key -> new FactDraft(require.asText("")));
                draft.readers.addAll(focus);
                if (beat.getChapterNo() != null) {
                    draft.revealedChapter = minChapter(draft.revealedChapter, beat.getChapterNo());
                }
            }
        }
        Map<String, DeriveFactDO> existing = new HashMap<>();
        for (DeriveFactDO row : factMapper.selectList(
                new LambdaQueryWrapper<DeriveFactDO>().eq(DeriveFactDO::getRunId, runId))) {
            existing.put(row.getFactKey(), row);
        }
        for (FactDraft draft : drafts.values()) {
            DeriveFactDO row = existing.get(draft.key);
            if (row == null) {
                row = new DeriveFactDO();
                row.setRunId(runId);
                row.setNovelId(novelId);
                row.setFactKey(draft.key);
                row.setStatus("OPEN");
                row.setContract("{}");
                factMapper.insertFull(row);
            }
            row.setCanonicalText(draft.canonicalText);
            row.setWriters(toJson(draft.writers));
            row.setReaders(toJson(draft.readers));
            row.setPlantedChapter(draft.plantedChapter);
            row.setRevealedChapter(draft.revealedChapter);
            factMapper.updateFull(row);
        }
    }

    /** 记录一次确定性修复。 */
    public void recordRepair(long runId, String stage, String repairType,
                             Object before, Object after, String reason, String target) {
        DeriveRepairLogDO row = new DeriveRepairLogDO();
        row.setRunId(runId);
        row.setStage(stage);
        row.setRepairType(repairType);
        row.setBeforeJson(toJson(before));
        row.setAfterJson(toJson(after));
        row.setReason(reason == null ? "" : reason);
        row.setTarget(target);
        repairLogMapper.insertFull(row);
    }

    static String factKey(String canonicalText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return "F-" + HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static int minChapter(Integer current, int candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> out.add(item.asText("")));
        }
        return out;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static final class FactDraft {
        private final String key;
        private final String canonicalText;
        private final Set<String> writers = new LinkedHashSet<>();
        private final Set<String> readers = new LinkedHashSet<>();
        private Integer plantedChapter;
        private Integer revealedChapter;

        private FactDraft(String canonicalText) {
            this.canonicalText = canonicalText;
            this.key = factKey(canonicalText);
        }
    }
}