package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P1 ThreadLedger：根据已落库拍合同同步线程状态。
 *
 * <p>线程状态仍是 derive_story_thread 的业务真源；本服务只做派生状态同步。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveThreadService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BeatInstanceMapper beatInstanceMapper;
    private final StoryThreadMapper storyThreadMapper;

    /** 同步 OPEN/ECHOED/PAYOFF 状态；不创建新线程。 */
    public void syncFromBeats(long runId) {
        List<StoryThreadDO> threads = storyThreadMapper.selectList(
                new LambdaQueryWrapper<StoryThreadDO>().eq(StoryThreadDO::getRunId, runId));
        if (threads.isEmpty()) {
            return;
        }
        List<BeatInstanceDO> beats = beatInstanceMapper.selectList(
                new LambdaQueryWrapper<BeatInstanceDO>()
                        .eq(BeatInstanceDO::getRunId, runId)
                        .eq(BeatInstanceDO::getStatus, "PLANNED"));
        Set<String> echoed = new HashSet<>();
        Set<String> paid = new HashSet<>();
        for (BeatInstanceDO beat : beats) {
            JsonNode route = readTreeSafe(beat.getRouteContract());
            for (JsonNode node : route.path("threadRefs")) {
                String key = node.asText("");
                if (isPayoffRole(route.path("beatRole").asText(""))) {
                    paid.add(key);
                } else {
                    echoed.add(key);
                }
            }
        }
        for (StoryThreadDO thread : threads) {
            String next = paid.contains(thread.getThreadKey())
                    ? "PAYOFF"
                    : echoed.contains(thread.getThreadKey())
                            ? "ECHOED"
                            : "OPEN";
            if (!next.equals(thread.getStatus())) {
                thread.setStatus(next);
                storyThreadMapper.updateById(thread);
                log.info("线程状态同步：runId={} threadKey={} status={}",
                        runId, thread.getThreadKey(), next);
            }
        }
    }

    private static boolean isPayoffRole(String role) {
        return "CLUE_PAYOFF".equals(role)
                || "LOCAL_CLOSURE".equals(role)
                || "FINAL_REVEAL".equals(role)
                || "FINAL_CLOSURE".equals(role);
    }

    private static JsonNode readTreeSafe(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}