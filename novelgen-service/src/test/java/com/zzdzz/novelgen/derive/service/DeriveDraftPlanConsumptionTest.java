package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanJsonViews;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ⑥b消费故事规划合同。 */
@ExtendWith(MockitoExtension.class)
class DeriveDraftPlanConsumptionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private VolumePlanMapper volumePlanMapper;
    @Mock
    private CharacterPlanMapper characterPlanMapper;
    @Mock
    private StoryThreadMapper storyThreadMapper;
    @InjectMocks
    private DeriveDraftService service;

    @BeforeEach
    void injectOptionalMappers() {
        ReflectionTestUtils.setField(service, "volumePlanMapper", volumePlanMapper);
        ReflectionTestUtils.setField(service, "characterPlanMapper", characterPlanMapper);
        ReflectionTestUtils.setField(service, "storyThreadMapper", storyThreadMapper);
    }

    @Test
    void batchInputContainsVolumeChapterAndCharacterContracts() throws Exception {
        when(volumePlanMapper.selectOne(any())).thenReturn(volume());
        when(characterPlanMapper.selectList(any())).thenReturn(List.of(character()));
        when(storyThreadMapper.selectList(any())).thenReturn(List.of(thread()));

        String user = service.buildBatchUser(run(), List.of(chapter(2)), List.of(2),
                List.of(beat(11, 2)), Map.of(11, "林雾进入封锁区"), "",
                assets(), "", MAPPER.createArrayNode());

        assertTrue(user.contains("VC2-LOCAL-TRUTH"));
        assertTrue(user.contains("\"planContract\""));
        assertTrue(user.contains("\"characterKey\":\"C2\""));
        assertTrue(user.contains("\"storyLayer\":\"CHARACTER_ARC\""));
    }

    @Test
    void batchParserCannotMoveBeatToAnotherChapter() throws Exception {
        DeriveDraftService.BatchContext ctx = new DeriveDraftService.BatchContext(
                List.of(11), List.of(2), Set.of("林雾"), Map.of(11, 3));
        String moved = """
                {"chapters":[{"no":2,"title":"a","draft_outline":"纲","time":"当晚",
                  "state_changes":[],"beats":[{"seq":11,"filled":"林雾进入封锁区"}]}],
                 "skipped":[]}
                """;

        assertThrows(LlmJson.Bad.class,
                () -> DeriveDraftService.parseBatch(MAPPER.readTree(moved), ctx));
    }

    @Test
    void planningJsonMappersSupportEntityTimestamps() {
        CharacterPlanDO row = character();
        row.setCreatedTime(OffsetDateTime.now());
        row.setUpdatedTime(OffsetDateTime.now());

        var json = PlanJsonViews.character(row);
        assertTrue(json.has("characterKey"));
        assertTrue(!json.has("createdTime"));
    }

    private static RunDO run() {
        RunDO run = new RunDO();
        run.setId(9L);
        run.setNovelId(20L);
        run.setDomainSnapshot("{}");
        run.setParamTags("{\"推理\":1}");
        run.setParamPace(new BigDecimal("0.75"));
        run.setParamOutcome("闭合");
        run.setParamForm("LIGHT_NOVEL");
        return run;
    }

    private static NovelDO novel() {
        NovelDO novel = new NovelDO();
        novel.setId(20L);
        novel.setStoryAssets("""
                {"protagonist":"顾砚","cast":[
                  {"name":"顾砚","characterKey":"C1"},
                  {"name":"林雾","characterKey":"C2"}
                ]}
                """);
        return novel;
    }

    private static com.fasterxml.jackson.databind.JsonNode assets() throws Exception {
        return MAPPER.readTree(novel().getStoryAssets());
    }

    private static ChapterDO chapter(int no) {
        ChapterDO chapter = new ChapterDO();
        chapter.setChapterNo(no);
        chapter.setVolumeNo(2);
        chapter.setTitle("旧身份");
        chapter.setBudget(2800);
        chapter.setPlanContract("""
                {"chapterFunction":"MIDPOINT_REVERSAL",
                 "requiredBeatRoles":["FUNCTION_USE"]}
                """);
        return chapter;
    }

    private static BeatInstanceDO beat(int seq, int chapterNo) {
        BeatInstanceDO beat = new BeatInstanceDO();
        beat.setSeq(seq);
        beat.setChapterNo(chapterNo);
        beat.setBeatType("抉择");
        beat.setPacing(new BigDecimal("0.75"));
        beat.setStoryLayer("CHARACTER_ARC");
        beat.setBeatRole("FUNCTION_USE");
        beat.setRouteContract("""
                {"focusCharacters":["C2"],"appearanceMode":"LIVE",
                 "threadRefs":["REL-C1-C2"]}
                """);
        return beat;
    }

    private static VolumePlanDO volume() {
        VolumePlanDO volume = new VolumePlanDO();
        volume.setVolumeNo(2);
        volume.setFromChapter(13);
        volume.setToChapter(24);
        volume.setLocalEvent("重复事故");
        volume.setLocalClosure("VC2-LOCAL-TRUTH");
        volume.setNextHook("进入第三卷");
        volume.setGlobalRole("LOCAL");
        volume.setContract("""
                {"volumeNo":2,"fromChapter":13,"toChapter":24,"arc":"重复事故",
                 "localQuestion":"事故为何重演","falseAnswer":"巧合","trueAnswer":"同一手法",
                 "mainMysteryAdvance":"手法重复","focusCharacters":["C2"],
                 "relationshipChanges":["REL-C1-C2"],"requiredThreads":["REL-C1-C2"],
                 "forbiddenReveals":[],"localClosureProof":"VC2-LOCAL-TRUTH",
                 "nextHook":"进入第三卷","globalRole":"LOCAL"}
                """);
        return volume;
    }

    private static CharacterPlanDO character() {
        CharacterPlanDO character = new CharacterPlanDO();
        character.setCharacterKey("C2");
        character.setDisplayNameHint("林雾");
        character.setSpotlightVolume(2);
        return character;
    }

    private static StoryThreadDO thread() {
        StoryThreadDO thread = new StoryThreadDO();
        thread.setThreadKey("REL-C1-C2");
        thread.setKind("RELATIONSHIP");
        thread.setTitle("信任破裂");
        thread.setStatus("OPEN");
        thread.setContract("{}");
        return thread;
    }
}
