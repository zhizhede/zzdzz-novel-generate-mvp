package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.AssetBeatMapper;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.AssetWorldMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CalibrationMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.SourceChapterMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.StoryPlanDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 故事规划读模型。 */
@ExtendWith(MockitoExtension.class)
class DeriveStoryPlanQueryTest {

    @Mock private RunMapper runMapper;
    @Mock private NovelMapper novelMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private BeatInstanceMapper beatInstanceMapper;
    @Mock private QcReportMapper qcReportMapper;
    @Mock private CalibrationMapper calibrationMapper;
    @Mock private SourceChapterMapper sourceChapterMapper;
    @Mock private AssetOutlineMapper assetOutlineMapper;
    @Mock private AssetBeatMapper assetBeatMapper;
    @Mock private AssetWorldMapper assetWorldMapper;
    @Mock private LabelIndexMapper labelIndexMapper;
    @Mock private com.zzdzz.novelgen.mapper.LlmCallLogMapper llmCallLogMapper;
    @Mock private VolumePlanMapper volumePlanMapper;
    @Mock private CharacterPlanMapper characterPlanMapper;
    @Mock private StoryThreadMapper storyThreadMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private DeriveQueryService service;

    @BeforeEach
    void injectOptionalMappers() {
        ReflectionTestUtils.setField(service, "volumePlanMapper", volumePlanMapper);
        ReflectionTestUtils.setField(service, "characterPlanMapper", characterPlanMapper);
        ReflectionTestUtils.setField(service, "storyThreadMapper", storyThreadMapper);
    }

    @Test
    void storyPlanDetailReturnsVolumesCharactersThreadsAndSlots() {
        RunDO run = new RunDO();
        run.setId(9L);
        run.setNovelId(20L);
        run.setStepStatus("""
                {"steps":{"PLAN_V2":{"currentUnit":"DONE","scope":
                  {"decision":"MULTI_VOLUME","volumeCount":4,"chapterCount":48,"chapterAvg":2800}}}}
                """);
        when(runMapper.selectById(9L)).thenReturn(run);
        when(volumePlanMapper.selectList(any())).thenReturn(volumes());
        when(characterPlanMapper.selectList(any())).thenReturn(characters());
        when(storyThreadMapper.selectList(any())).thenReturn(List.of(thread()));
        when(chapterMapper.selectList(any())).thenReturn(chapters());

        StoryPlanDetailVO detail = service.storyPlan(9L);

        assertEquals(4, detail.volumes().size());
        assertEquals(3, detail.characters().size());
        assertEquals(48, detail.chapterSlots().size());
        assertEquals("MULTI_VOLUME", detail.scope().path("decision").asText());
    }

    private static List<VolumePlanDO> volumes() {
        List<VolumePlanDO> out = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            VolumePlanDO row = new VolumePlanDO();
            row.setVolumeNo(i);
            row.setFromChapter((i - 1) * 12 + 1);
            row.setToChapter(i * 12);
            row.setContract("{}");
            out.add(row);
        }
        return out;
    }

    private static List<CharacterPlanDO> characters() {
        return List.of(character("C1"), character("C2"), character("C3"));
    }

    private static CharacterPlanDO character(String key) {
        CharacterPlanDO row = new CharacterPlanDO();
        row.setCharacterKey(key);
        row.setContract("{}");
        return row;
    }

    private static StoryThreadDO thread() {
        StoryThreadDO row = new StoryThreadDO();
        row.setThreadKey("GM-CLUE-01");
        row.setKind("CLUE");
        row.setStatus("OPEN");
        row.setContract("{}");
        return row;
    }

    private static List<ChapterDO> chapters() {
        List<ChapterDO> out = new ArrayList<>();
        for (int no = 1; no <= 48; no++) {
            ChapterDO chapter = new ChapterDO();
            chapter.setChapterNo(no);
            chapter.setPlanContract("{}");
            out.add(chapter);
        }
        return out;
    }
}
