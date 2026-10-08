package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.service.data.VolumeReviewDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M2 注入分流接线测试：提示词衍生段由 DeriveProfile.injectSegment 单读口分派
 * （替换旧 ContextPacker 的 migrate() 二选一），锁三态——
 * ORIGINAL+样本→差异红线；取材产线（RESKIN/归档 MIGRATE）+样本→蓝图口径段；无样本→不注入。
 */
class ContextPackerDeriveSegmentTest {

    private ContextPackerService packerWith(String deriveJson) {
        NovelDataService novelData = mock(NovelDataService.class);
        when(novelData.findDeriveConfig(1L)).thenReturn(deriveJson);
        PromptTemplateDataService promptDao = mock(PromptTemplateDataService.class);
        when(promptDao.findAll()).thenReturn(List.of());
        return new ContextPackerService(
                mock(StylePackDataService.class), mock(CanonDocDataService.class),
                mock(DigestDataService.class), mock(ForeshadowDataService.class),
                mock(ChapterDataService.class), mock(WorldStateDataService.class),
                mock(MaterialCardService.class), mock(TuningService.class),
                mock(EmbeddingService.class), new PromptTemplateService(promptDao),
                mock(VolumeReviewDataService.class), novelData,
                mock(GateService.class), mock(GateReportDataService.class), new ObjectMapper());
    }

    @Test
    void originalWithSampleInjectsRedlineSegment() {
        String out = packerWith("{\"mode\":\"ORIGINAL\",\"sourceSampleId\":9}").deriveVolumeSection(1L);
        assertThat(out).contains("衍生差异红线");
        assertThat(out).doesNotContain("剧情迁移（最高优先级）");
    }

    @Test
    void reskinWithSampleInjectsBlueprintSegment() {
        String out = packerWith("{\"mode\":\"RESKIN\",\"sourceSampleId\":9}").deriveVolumeSection(1L);
        assertThat(out).contains("剧情迁移（最高优先级）");
        assertThat(out).doesNotContain("衍生差异红线");
    }

    /** D1 回归：历史 MIGRATE 行（归档 RESKIN）续写仍走蓝图口径段，不被差异红线压制。 */
    @Test
    void legacyMigrateRowInjectsBlueprintSegmentNotRedline() {
        String out = packerWith("{\"mode\":\"MIGRATE\",\"sourceSampleId\":9}").deriveVolumeSection(1L);
        assertThat(out).contains("剧情迁移（最高优先级）");
        assertThat(out).doesNotContain("衍生差异红线");
    }

    @Test
    void noSampleInjectsNeitherSegment() {
        String out = packerWith("{\"water\":50}").deriveVolumeSection(1L);
        assertThat(out).doesNotContain("衍生差异红线");
        assertThat(out).doesNotContain("剧情迁移（最高优先级）");
    }
}
