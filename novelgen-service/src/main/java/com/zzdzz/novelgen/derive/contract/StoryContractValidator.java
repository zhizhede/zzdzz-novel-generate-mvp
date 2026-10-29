package com.zzdzz.novelgen.derive.contract;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 合同的确定性校验器（纯函数，无 IO）。
 *
 * <p>两条用途：① 合同生成时的结构校验（失败原因喂回 LLM 重试）；② 下游节点产出与合同比对，
 * 任何新的 culprit/motive/method、cast 姓名、线程 key 或线索体系都必须在这里被判为漂移。
 * 错误码形如 {@code CONTRACT_CAST_LIMIT：…}，供日志与 P6 预检直接引用。
 */
public final class StoryContractValidator {

    /** 线索 key 形如 GM-CLUE-01。 */
    private static final Pattern CLUE_KEY = Pattern.compile("GM-CLUE-\\d+");

    private StoryContractValidator() {
    }

    /** 合同结构校验；返回空列表表示通过。 */
    public static List<String> validate(StoryContract contract, PlanContracts.ScopeContract scope) {
        List<String> errors = new ArrayList<>();
        if (contract == null) {
            errors.add("CONTRACT_MISSING：合同为空");
            return errors;
        }
        PlanContracts.ScopeContract boundary = contract.scope() == null ? scope : contract.scope();
        if (boundary == null) {
            errors.add("CONTRACT_SCOPE_MISSING：缺少规模合同");
            return errors;
        }
        validateCast(contract, boundary, errors);
        validateTruth(contract, boundary, errors);
        validateClues(contract, boundary, errors);
        validateThreads(contract, boundary, errors);
        validateForbidden(contract, errors);
        validateVolumeTruth(contract, boundary, errors);
        return errors;
    }

    /**
     * 只用于新合同生成（freeze 前）；旧合同读取仍走宽松 validate。
     *
     * <p>新合同必须在 reveal 章兑现至少一条线索，并提供可供下游逐字引用的
     * motiveTag/methodTag。tags 是校验辅助字段，不参与旧合同案情漂移判定。
     */
    public static List<String> validateForFreeze(StoryContract contract,
                                                 PlanContracts.ScopeContract scope) {
        List<String> errors = new ArrayList<>(validate(contract, scope));
        if (contract == null || contract.truth() == null) {
            return errors;
        }
        if (contract.truth().revealChapter() > 0 && contract.clues() != null) {
            boolean hasPayoff = contract.clues().stream()
                    .anyMatch(clue -> clue != null
                            && clue.payoffChapter() == contract.truth().revealChapter());
            if (!hasPayoff) {
                errors.add("CONTRACT_REVEAL_NO_PAYOFF：revealChapter="
                        + contract.truth().revealChapter() + " 没有任何 clue 在该章兑现");
            }
        }
        validateFreezeTag("motiveTag", contract.truth().motiveTag(), errors);
        validateFreezeTag("methodTag", contract.truth().methodTag(), errors);
        return errors;
    }

    private static void validateFreezeTag(String field, String value, List<String> errors) {
        String tag = value == null ? "" : value.trim();
        if (tag.length() < 4 || tag.length() > 20) {
            errors.add("CONTRACT_TAG_MISSING：" + field
                    + " 必须为非空核心短语（4~20 字），实际「" + tag + "」");
        }
    }
    private static void validateCast(StoryContract contract, PlanContracts.ScopeContract scope,
                                     List<String> errors) {
        List<StoryContract.CharacterSlot> cast = contract.cast();
        if (cast == null || cast.size() != StoryContract.MAX_NAMED_CHARACTERS) {
            errors.add("CONTRACT_CAST_LIMIT：cast 必须恰好 "
                    + StoryContract.MAX_NAMED_CHARACTERS + " 人（实际 "
                    + (cast == null ? 0 : cast.size()) + "）");
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        boolean opposition = false;
        for (int i = 0; i < cast.size(); i++) {
            StoryContract.CharacterSlot slot = cast.get(i);
            String expectKey = "C" + (i + 1);
            if (slot.key() == null || !expectKey.equals(slot.key())) {
                errors.add("CONTRACT_CAST_KEY：第 " + (i + 1) + " 个角色 key 必须是 " + expectKey
                        + "（实际 " + slot.key() + "）");
            } else if (!keys.add(slot.key())) {
                errors.add("CONTRACT_CAST_KEY：角色 key 重复 " + slot.key());
            }
            if (slot.name() == null || slot.name().isBlank()) {
                errors.add("CONTRACT_CAST_NAME：角色 " + slot.key() + " 姓名为空");
            } else if (!names.add(slot.name())) {
                errors.add("CONTRACT_CAST_NAME：角色姓名重复「" + slot.name() + "」");
            }
            if ("OPPOSITION".equalsIgnoreCase(slot.role() == null ? "" : slot.role().trim())) {
                opposition = true;
            }
        }
        if (!opposition) {
            errors.add("CONTRACT_CAST_ROLE：三名角色里必须有且仅有一名 OPPOSITION");
        }
    }

    private static void validateTruth(StoryContract contract, PlanContracts.ScopeContract scope,
                                      List<String> errors) {
        StoryContract.Truth truth = contract.truth();
        if (truth == null) {
            errors.add("CONTRACT_TRUTH_MISSING：缺少 truth");
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        contract.cast().forEach(slot -> keys.add(slot.key()));
        if (truth.culpritKey() == null || !keys.contains(truth.culpritKey())) {
            errors.add("CONTRACT_CULPRIT_NOT_IN_CAST：culpritKey=" + truth.culpritKey()
                    + " 不在 cast " + keys);
        } else {
            String expect = contract.castName(truth.culpritKey());
            if (truth.culpritName() == null || !expect.equals(truth.culpritName())) {
                errors.add("CONTRACT_CULPRIT_NAME：culpritName 必须等于 cast 中该角色的姓名（期望 "
                        + expect + "，实际 " + truth.culpritName() + "）");
            }
        }
        if (isBlank(truth.motive())) {
            errors.add("CONTRACT_TRUTH_FIELD：motive 不能为空");
        }
        if (isBlank(truth.method())) {
            errors.add("CONTRACT_TRUTH_FIELD：method 不能为空");
        }
        if (isBlank(truth.finalTruth())) {
            errors.add("CONTRACT_TRUTH_FIELD：finalTruth 不能为空");
        }
        if (truth.revealChapter() < 1 || truth.revealChapter() > scope.chapterCount()) {
            errors.add("CONTRACT_REVEAL_RANGE：revealChapter=" + truth.revealChapter()
                    + " 越界（1.." + scope.chapterCount() + "）");
        }
    }

    private static void validateClues(StoryContract contract, PlanContracts.ScopeContract scope,
                                      List<String> errors) {
        List<StoryContract.Clue> clues = contract.clues();
        if (clues == null || clues.size() < 3) {
            errors.add("CONTRACT_CLUE_MIN：clues 至少 3 条（实际 "
                    + (clues == null ? 0 : clues.size()) + "）");
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> castKeys = new LinkedHashSet<>();
        contract.cast().forEach(slot -> castKeys.add(slot.key()));
        for (StoryContract.Clue clue : clues) {
            if (clue.clueKey() == null || !CLUE_KEY.matcher(clue.clueKey()).matches()) {
                errors.add("CONTRACT_CLUE_KEY：clueKey 必须形如 GM-CLUE-01（实际 "
                        + clue.clueKey() + "）");
            } else if (!keys.add(clue.clueKey())) {
                errors.add("CONTRACT_CLUE_KEY：clueKey 重复 " + clue.clueKey());
            }
            if (isBlank(clue.clue())) {
                errors.add("CONTRACT_CLUE_TEXT：" + clue.clueKey() + " 的 clue 为空");
            }
            if (clue.plantedChapter() < 1 || clue.plantedChapter() > scope.chapterCount()
                    || clue.payoffChapter() < 1 || clue.payoffChapter() > scope.chapterCount()
                    || clue.payoffChapter() <= clue.plantedChapter()) {
                errors.add("CONTRACT_CLUE_RANGE：" + clue.clueKey() + " 埋设/兑现章非法（plant="
                        + clue.plantedChapter() + ", payoff=" + clue.payoffChapter()
                        + ", chapterCount=" + scope.chapterCount() + "）");
            }
            if (!isBlank(clue.ownerKey()) && !castKeys.contains(clue.ownerKey())) {
                errors.add("CONTRACT_CLUE_OWNER：" + clue.clueKey() + " 的 ownerKey="
                        + clue.ownerKey() + " 不在 cast");
            }
        }
    }

    private static void validateThreads(StoryContract contract, PlanContracts.ScopeContract scope,
                                        List<String> errors) {
        List<StoryContract.ThreadRef> threads = contract.threads();
        if (threads == null || threads.isEmpty()) {
            errors.add("CONTRACT_THREAD_MISSING：threads 不能为空");
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> castKeys = new LinkedHashSet<>();
        contract.cast().forEach(slot -> castKeys.add(slot.key()));
        for (StoryContract.ThreadRef thread : threads) {
            if (isBlank(thread.threadKey())) {
                errors.add("CONTRACT_THREAD_KEY：threadKey 为空");
                continue;
            }
            if (!keys.add(thread.threadKey())) {
                errors.add("CONTRACT_THREAD_KEY：threadKey 重复 " + thread.threadKey());
            }
            if (thread.openedVolume() < 1 || thread.openedVolume() > scope.volumeCount()
                    || thread.payoffVolume() < 1 || thread.payoffVolume() > scope.volumeCount()
                    || thread.payoffVolume() < thread.openedVolume()) {
                errors.add("CONTRACT_THREAD_VOLUME：" + thread.threadKey() + " 卷号非法（open="
                        + thread.openedVolume() + ", payoff=" + thread.payoffVolume()
                        + ", volumeCount=" + scope.volumeCount() + "）");
            }
            if (!isBlank(thread.ownerKey()) && !castKeys.contains(thread.ownerKey())) {
                errors.add("CONTRACT_THREAD_OWNER：" + thread.threadKey() + " 的 ownerKey="
                        + thread.ownerKey() + " 不在 cast");
            }
        }
        Set<String> clueKeys = new LinkedHashSet<>();
        contract.clues().forEach(clue -> clueKeys.add(clue.clueKey()));
        for (String clueKey : clueKeys) {
            if (!keys.contains(clueKey)) {
                errors.add("CONTRACT_THREAD_COVERAGE：线索 " + clueKey + " 没有对应线程");
            }
        }
    }

    private static void validateForbidden(StoryContract contract, List<String> errors) {
        List<String> forbidden = contract.forbidden();
        if (forbidden == null) {
            errors.add("CONTRACT_FORBIDDEN_SHAPE：forbidden 必须是字符串数组");
            return;
        }
        for (String item : forbidden) {
            if (item == null || item.isBlank()) {
                errors.add("CONTRACT_FORBIDDEN_SHAPE：forbidden 含空项");
                continue;
            }
            String trimmed = item.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                errors.add("CONTRACT_FORBIDDEN_SHAPE：forbidden 必须是字符串数组，"
                        + "禁止把 JSON 数组写成一个字符串：" + trimmed);
            }
        }
    }

    private static void validateVolumeTruth(StoryContract contract,
                                            PlanContracts.ScopeContract scope,
                                            List<String> errors) {
        List<StoryContract.VolumeTruth> slices = contract.volumeTruth();
        if (slices == null || slices.isEmpty()) {
            errors.add("CONTRACT_VOLUME_TRUTH_MISSING：volumeTruth 不能为空");
            return;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (StoryContract.VolumeTruth slice : slices) {
            if (slice.volumeNo() < 1 || slice.volumeNo() > scope.volumeCount()) {
                errors.add("CONTRACT_VOLUME_TRUTH_RANGE：volumeNo=" + slice.volumeNo() + " 越界");
                continue;
            }
            if (!seen.add(slice.volumeNo())) {
                errors.add("CONTRACT_VOLUME_TRUTH_RANGE：volumeNo=" + slice.volumeNo() + " 重复");
            }
            if (isBlank(slice.reveal())) {
                errors.add("CONTRACT_VOLUME_TRUTH_EMPTY：第 " + slice.volumeNo() + " 卷 reveal 为空");
            }
        }
        for (int volumeNo = 1; volumeNo <= scope.volumeCount(); volumeNo++) {
            if (!seen.contains(volumeNo)) {
                errors.add("CONTRACT_VOLUME_TRUTH_MISSING：缺少第 " + volumeNo + " 卷真相切片");
            }
        }
    }

    /**
     * 两份合同的“同一性”判定：cast（key/姓名/职能）、真相核、线索 key 与章号、线程 key、
     * 禁令任一不同即视为漂移。用于断言冻结后的合同没有被下游改写。
     */
    public static boolean sameCanonical(StoryContract a, StoryContract b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (!canonicalCast(a).equals(canonicalCast(b))) {
            return false;
        }
        StoryContract.Truth ta = a.truth();
        StoryContract.Truth tb = b.truth();
        if (ta == null || tb == null) {
            return ta == tb;
        }
        if (!ta.culpritKey().equals(tb.culpritKey())
                || !ta.culpritName().equals(tb.culpritName())
                || !ta.motive().equals(tb.motive())
                || !ta.method().equals(tb.method())
                || !ta.finalTruth().equals(tb.finalTruth())
                || !ta.finalChoice().equals(tb.finalChoice())
                || ta.revealChapter() != tb.revealChapter()) {
            return false;
        }
        if (!canonicalClues(a).equals(canonicalClues(b))) {
            return false;
        }
        if (!canonicalThreads(a).equals(canonicalThreads(b))) {
            return false;
        }
        return canonicalTexts(a.forbidden()).equals(canonicalTexts(b.forbidden()));
    }

    /**
     * 角色叙事计划与合同 cast 的一致性：key 必须来自合同、姓名不许改、职能必须落在合同槽位上。
     *
     * @return 漂移错误码列表（空=一致）
     */
    public static List<String> validateCharacters(List<PlanContracts.CharacterContract> characters,
                                                  StoryContract contract) {
        List<String> errors = new ArrayList<>();
        if (contract == null) {
            errors.add("CONTRACT_MISSING：合同为空");
            return errors;
        }
        if (characters == null || characters.isEmpty()) {
            errors.add("CONTRACT_CAST_DRIFT：角色叙事计划为空");
            return errors;
        }
        Set<String> contractKeys = new LinkedHashSet<>();
        contract.cast().forEach(slot -> contractKeys.add(slot.key()));
        for (PlanContracts.CharacterContract character : characters) {
            if (!contractKeys.contains(character.characterKey())) {
                errors.add("CONTRACT_CAST_DRIFT：角色 key " + character.characterKey()
                        + " 不在合同 cast " + contractKeys);
                continue;
            }
            String contractRole = roleOf(contract, character.characterKey());
            String planRole = character.function() == null ? "" : character.function().name();
            if (!contractRole.isBlank() && !contractRole.equalsIgnoreCase(planRole)) {
                errors.add("CONTRACT_ROLE_DRIFT：" + character.characterKey() + " 的 function="
                        + planRole + " 与合同槽位 " + contractRole + " 不一致");
            }
        }
        for (StoryContract.CharacterSlot slot : contract.cast()) {
            boolean present = characters.stream()
                    .anyMatch(c -> slot.key().equals(c.characterKey()));
            if (!present) {
                errors.add("CONTRACT_CAST_DRIFT：合同角色 " + slot.key() + "（" + slot.name()
                        + "）在角色叙事计划中缺失");
            }
        }
        return errors;
    }

    /**
     * P6 前的合同一致性预检：只查“结构漂移”（角色/线程/禁令/泄底），不查文笔。
     *
     * @return 结构漂移错误（空=合同与计划一致）
     */
    public static List<String> validateAgainstPlan(StoryContract contract,
                                                   List<PlanContracts.VolumeContract> volumes,
                                                   List<PlanContracts.CharacterContract> characters,
                                                   List<ChapterDO> chapters) {
        List<String> errors = new ArrayList<>();
        if (contract == null) {
            return List.of("CONTRACT_MISSING：合同为空");
        }
        errors.addAll(validateCharacters(characters, contract));
        Set<String> threadKeys = new LinkedHashSet<>();
        contract.threads().forEach(thread -> threadKeys.add(thread.threadKey()));
        Set<String> castKeys = new LinkedHashSet<>();
        contract.cast().forEach(slot -> castKeys.add(slot.key()));
        if (volumes != null) {
            for (PlanContracts.VolumeContract volume : volumes) {
                for (String key : volume.requiredThreads()) {
                    if (!threadKeys.contains(key)) {
                        errors.add("CONTRACT_THREAD_DRIFT：第 " + volume.volumeNo()
                                + " 卷 requiredThreads 含合同外线程 " + key);
                    }
                }
                for (String key : volume.focusCharacters()) {
                    if (!castKeys.contains(key)) {
                        errors.add("CONTRACT_CAST_DRIFT：第 " + volume.volumeNo()
                                + " 卷 focusCharacters 含合同外角色 " + key);
                    }
                }
            }
        }
        if (chapters != null && contract.truth() != null) {
            String culprit = contract.truth().culpritName();
            String method = contract.truth().method();
            int reveal = contract.truth().revealChapter();
            if (!isBlank(culprit) && !isBlank(method)) {
                for (ChapterDO chapter : chapters) {
                    String outline = chapter.getDraftOutline();
                    if (outline == null || chapter.getChapterNo() >= reveal) {
                        continue;
                    }
                    if (outline.contains(culprit) && outline.contains(method)) {
                        errors.add("CONTRACT_SPOILER：第 " + chapter.getChapterNo()
                                + " 章在揭示章 " + reveal + " 前写出了真凶与手法结论");
                    }
                }
            }
        }
        return errors;
    }

    private static String roleOf(StoryContract contract, String key) {
        for (StoryContract.CharacterSlot slot : contract.cast()) {
            if (key.equals(slot.key())) {
                return slot.role() == null ? "" : slot.role().trim();
            }
        }
        return "";
    }

    private static Set<String> canonicalCast(StoryContract contract) {
        Set<String> out = new LinkedHashSet<>();
        contract.cast().forEach(slot -> out.add(slot.key() + "|" + slot.name() + "|"
                + (slot.role() == null ? "" : slot.role().trim())));
        return out;
    }

    private static Set<String> canonicalClues(StoryContract contract) {
        Set<String> out = new LinkedHashSet<>();
        contract.clues().forEach(clue -> out.add(clue.clueKey() + "|" + clue.plantedChapter()
                + "|" + clue.payoffChapter()));
        return out;
    }

    private static Set<String> canonicalThreads(StoryContract contract) {
        Set<String> out = new LinkedHashSet<>();
        contract.threads().forEach(thread -> out.add(thread.threadKey() + "|"
                + thread.openedVolume() + "|" + thread.payoffVolume()));
        return out;
    }

    private static Set<String> canonicalTexts(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            values.forEach(value -> out.add(value == null ? "" : value.trim()));
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}