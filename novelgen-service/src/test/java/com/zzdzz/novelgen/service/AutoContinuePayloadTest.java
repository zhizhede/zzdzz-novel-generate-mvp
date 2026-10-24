package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务级自动续跑开关（2026-10-17）：/run 入参 autoContinue 落任务 payload
 * {"autoContinue":true}，CHAPTERS 任务 DONE 后据此接排下一批（书级无人续跑链优先）。
 * 缺 payload / 坏 JSON 一律不续——单章重生成、打回重排等入口不带该键，保持老口径。
 */
class AutoContinuePayloadTest {

    @Test
    void flagReadFromPayload() {
        assertThat(GenerationQueueService.taskAutoContinueOn("{\"autoContinue\":true}")).isTrue();
        assertThat(GenerationQueueService.taskAutoContinueOn("{\"autoContinue\":false}")).isFalse();
    }

    @Test
    void missingOrBrokenPayloadMeansNoContinue() {
        assertThat(GenerationQueueService.taskAutoContinueOn(null)).isFalse();
        assertThat(GenerationQueueService.taskAutoContinueOn("")).isFalse();
        assertThat(GenerationQueueService.taskAutoContinueOn("{}")).isFalse();
        assertThat(GenerationQueueService.taskAutoContinueOn("不是 JSON")).isFalse();
        assertThat(GenerationQueueService.taskAutoContinueOn("[1,2]")).isFalse();
    }

    @Test
    void writtenPayloadRoundTrips() {
        assertThat(GenerationQueueService.taskAutoContinueOn(
                GenerationQueueService.buildAutoContinuePayload())).isTrue();
    }
}
