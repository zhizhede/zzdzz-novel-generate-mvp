package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章纲批量任务的 payload 解析（2026-10-01）：勾了「含已有正文的章」时任务 payload 为
 * {"includeTextChapters":true}，执行侧据此放行成稿章（保全状态出纲）；其它情况一律按老口径（跳过）。
 */
class OutlineTaskPayloadTest {

    @Test
    void includeFlagReadFromPayload() {
        assertThat(GenerationQueueService.includeTextChapters("{\"includeTextChapters\":true}")).isTrue();
        assertThat(GenerationQueueService.includeTextChapters("{\"includeTextChapters\":false}")).isFalse();
    }

    @Test
    void missingOrBrokenPayloadMeansSkip() {
        assertThat(GenerationQueueService.includeTextChapters(null)).isFalse();
        assertThat(GenerationQueueService.includeTextChapters("")).isFalse();
        assertThat(GenerationQueueService.includeTextChapters("{}")).isFalse();
        assertThat(GenerationQueueService.includeTextChapters("不是 JSON")).isFalse();
        assertThat(GenerationQueueService.includeTextChapters("[1,2]")).isFalse();
    }
}
