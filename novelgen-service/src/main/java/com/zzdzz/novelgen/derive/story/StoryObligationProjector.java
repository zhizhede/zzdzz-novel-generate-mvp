package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.genre.GenreProfile;
import com.zzdzz.novelgen.derive.genre.GenreContract;
import com.zzdzz.novelgen.derive.genre.MysteryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通用义务投影器：把类型合同、角色合同、卷合同与章级日程合并成可执行叙事义务。
 */
public final class StoryObligationProjector {

    private StoryObligationProjector() {
    }

    /** 投影全部义务；重复义务键或日程外章号直接失败。 */
    public static List<StoryObligation> project(GenreProfile<?> profile, StoryContract contract,
                                                List<ChapterScheduleEntry> schedule,
                                                List<PlanContracts.VolumeContract> volumes,
                                                List<PlanContracts.CharacterContract> characters) {
        return project(profile, new MysteryContract(contract), schedule, volumes, characters);
    }

    /** 通用类型合同投影；旧悬疑入口通过 MysteryContract 兼容层复用。 */
    public static List<StoryObligation> project(GenreProfile<?> profile, GenreContract contract,
                                                List<ChapterScheduleEntry> schedule,
                                                List<PlanContracts.VolumeContract> volumes,
                                                List<PlanContracts.CharacterContract> characters) {
        List<ChapterScheduleEntry> rows = schedule == null ? List.of() : schedule;
        Map<Integer, VolumeRange> ranges = volumeRanges(rows);
        List<StoryObligation> merged = new ArrayList<>();
        if (profile != null) {
            merged.addAll(castProject(profile, contract, rows));
        }
        merged.addAll(projectCharacters(characters, ranges));
        merged.addAll(projectVolumes(volumes, ranges));

        Map<String, StoryObligation> unique = new LinkedHashMap<>();
        for (StoryObligation obligation : merged) {
            if (unique.putIfAbsent(obligation.obligationKey(), obligation) != null) {
                throw new IllegalArgumentException("义务 key 重复：" + obligation.obligationKey());
            }
        }
        Set<Integer> scheduledChapters = rows.stream()
                .map(ChapterScheduleEntry::chapterNo)
                .collect(Collectors.toSet());
        for (StoryObligation obligation : unique.values()) {
            if (!scheduledChapters.contains(obligation.chapterNo())) {
                throw new IllegalArgumentException("义务 " + obligation.obligationKey()
                        + " 的章号 " + obligation.chapterNo() + " 不在章级日程内");
            }
        }
        return List.copyOf(unique.values());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<StoryObligation> castProject(GenreProfile profile, GenreContract contract,
                                                      List<ChapterScheduleEntry> rows) {
        return profile.projectObligations(contract, rows);
    }

    private static List<StoryObligation> projectCharacters(
            List<PlanContracts.CharacterContract> characters, Map<Integer, VolumeRange> ranges) {
        if (characters == null) {
            return List.of();
        }
        List<StoryObligation> out = new ArrayList<>();
        for (PlanContracts.CharacterContract character : characters) {
            if (character.characterKey() == null || character.characterKey().isBlank()) {
                continue;
            }
            String key = character.characterKey();
            if (character.requiredSetup() != null && !character.requiredSetup().isEmpty()) {
                int chapterNo = firstChapter(ranges, character.spotlightVolume(),
                        character.conflictVolume());
                out.add(obligation(chapterNo, "INTRODUCE_ENTITY", key,
                        Map.of("setup", List.copyOf(character.requiredSetup()))));
            }
            if (hasText(character.highlightChoice())) {
                int chapterNo = firstChapter(ranges, character.conflictVolume(),
                        character.spotlightVolume());
                out.add(obligation(chapterNo, "CHARACTER_CHOICE", key,
                        Map.of("proof", character.highlightChoice())));
            }
            if (hasText(character.highlightCost())) {
                int choiceChapter = firstChapter(ranges, character.conflictVolume(),
                        character.spotlightVolume());
                int costChapter = nextChapter(ranges, choiceChapter);
                out.add(obligation(costChapter, "CHARACTER_COST", key,
                        Map.of("proof", character.highlightCost(), "afterChapter", choiceChapter)));
            }
        }
        return out;
    }

    private static List<StoryObligation> projectVolumes(
            List<PlanContracts.VolumeContract> volumes, Map<Integer, VolumeRange> ranges) {
        if (volumes == null) {
            return List.of();
        }
        List<StoryObligation> out = new ArrayList<>();
        for (PlanContracts.VolumeContract volume : volumes) {
            VolumeRange range = ranges.get(volume.volumeNo());
            if (range == null) {
                continue;
            }
            String target = "V" + volume.volumeNo();
            if (hasText(volume.localClosureProof())) {
                out.add(obligation(range.toChapter(), "LOCAL_CLOSURE", target,
                        Map.of("proof", volume.localClosureProof())));
            }
            if ("FINAL".equalsIgnoreCase(volume.globalRole())) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("globalRole", "FINAL");
                if (hasText(volume.localClosureProof())) {
                    payload.put("proof", volume.localClosureProof());
                }
                out.add(obligation(range.toChapter(), "FINAL_CLOSURE", target, payload));
            }
        }
        return out;
    }

    private static Map<Integer, VolumeRange> volumeRanges(List<ChapterScheduleEntry> rows) {
        Map<Integer, VolumeRange> out = new LinkedHashMap<>();
        for (ChapterScheduleEntry row : rows) {
            out.merge(row.volumeNo(), new VolumeRange(row.chapterNo(), row.chapterNo()),
                    (left, right) -> new VolumeRange(Math.min(left.fromChapter(), right.fromChapter()),
                            Math.max(left.toChapter(), right.toChapter())));
        }
        return out;
    }

    private static int firstChapter(Map<Integer, VolumeRange> ranges, Integer... volumeNos) {
        for (Integer volumeNo : volumeNos) {
            VolumeRange range = volumeNo == null ? null : ranges.get(volumeNo);
            if (range != null) {
                return range.fromChapter();
            }
        }
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("义务投影缺少章级日程");
        }
        return ranges.values().stream().mapToInt(VolumeRange::fromChapter).min().orElseThrow();
    }

    private static int nextChapter(Map<Integer, VolumeRange> ranges, int chapterNo) {
        return ranges.values().stream()
                .flatMap(range -> java.util.stream.IntStream.rangeClosed(
                        range.fromChapter(), range.toChapter()).boxed())
                .filter(chapter -> chapter > chapterNo)
                .min(Integer::compareTo)
                .orElse(chapterNo);
    }

    private static StoryObligation obligation(int chapterNo, String kind, String targetKey,
                                              Map<String, Object> payload) {
        return new StoryObligation(StoryObligation.obligationKey(chapterNo, kind, targetKey),
                chapterNo, kind, targetKey, true, false, "HARD", "GENRE_CONTRACT", payload);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record VolumeRange(int fromChapter, int toChapter) {
    }
}
