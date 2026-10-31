package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ⑥b BATCH 窗口义务校验器：在窗口落库前拦截提前兑现、重复兑现、缺义务、状态冲突与越界实体。
 */
@Component
public class DraftWindowObligationValidator {

    /** 一个 BATCH 窗口的结构化输入。 */
    public record DraftWindow(
            int fromChapter,
            int toChapter,
            List<ChapterDraft> chapters,
            List<BeatDraft> beats,
            Map<Integer, List<StateChange>> stateChanges,
            Map<Integer, List<KnowledgeEvent>> knowledgeEvents,
            Set<String> allowedEntityKeys,
            List<ClosingAct> closingActs,
            List<ClosureEvidence> closureEvidence) {

        public DraftWindow {
            chapters = chapters == null ? List.of() : List.copyOf(chapters);
            beats = beats == null ? List.of() : List.copyOf(beats);
            stateChanges = stateChanges == null ? Map.of() : Map.copyOf(stateChanges);
            knowledgeEvents = knowledgeEvents == null ? Map.of() : Map.copyOf(knowledgeEvents);
            allowedEntityKeys = allowedEntityKeys == null ? Set.of() : Set.copyOf(allowedEntityKeys);
            closingActs = closingActs == null ? List.of() : List.copyOf(closingActs);
            closureEvidence = closureEvidence == null ? List.of() : List.copyOf(closureEvidence);
        }

        public DraftWindow(int fromChapter, int toChapter, List<ChapterDraft> chapters,
                           List<BeatDraft> beats, Map<Integer, List<StateChange>> stateChanges,
                           Map<Integer, List<KnowledgeEvent>> knowledgeEvents,
                           Set<String> allowedEntityKeys) {
            this(fromChapter, toChapter, chapters, beats, stateChanges, knowledgeEvents,
                    allowedEntityKeys, List.of(), List.of());
        }
    }

    /** 窗口内一章的可校验投影。 */
    public record ChapterDraft(
            int chapterNo,
            String outline,
            List<String> clueActs,
            List<String> entityKeys,
            List<String> threadKeys,
            List<RomanceEvent> romanceEvents) {

        public ChapterDraft {
            clueActs = clueActs == null ? List.of() : List.copyOf(clueActs);
            entityKeys = entityKeys == null ? List.of() : List.copyOf(entityKeys);
            threadKeys = threadKeys == null ? List.of() : List.copyOf(threadKeys);
            outline = outline == null ? "" : outline;
            romanceEvents = romanceEvents == null ? List.of() : List.copyOf(romanceEvents);
        }

        public ChapterDraft(int chapterNo, String outline, List<String> clueActs,
                            List<String> entityKeys, List<String> threadKeys) {
            this(chapterNo, outline, clueActs, entityKeys, threadKeys, List.of());
        }
    }

    /** 言情阶段事件证据。 */
    public record RomanceEvent(String stage, String targetKey, String threadKey, String evidence) {
    }

    /** 窗口内一拍的合同引用。 */
    public record BeatDraft(
            int seq,
            int chapterNo,
            String role,
            List<String> threadRefs,
            List<String> actorKeys,
            List<String> targetKeys,
            List<ThreadEffect> threadEffects,
            List<KnowledgeEffect> knowledgeEffects,
            String filled) {

        public BeatDraft {
            threadRefs = threadRefs == null ? List.of() : List.copyOf(threadRefs);
            actorKeys = actorKeys == null ? List.of() : List.copyOf(actorKeys);
            targetKeys = targetKeys == null ? List.of() : List.copyOf(targetKeys);
            threadEffects = threadEffects == null ? List.of() : List.copyOf(threadEffects);
            knowledgeEffects = knowledgeEffects == null ? List.of() : List.copyOf(knowledgeEffects);
            filled = filled == null ? "" : filled;
        }

        public BeatDraft(int seq, int chapterNo, String role, List<String> threadRefs,
                         List<String> actorKeys, List<String> targetKeys) {
            this(seq, chapterNo, role, threadRefs, actorKeys, targetKeys,
                    List.of(), List.of(), "");
        }
    }

    /** 一拍携带的线程效果证据。 */
    public record ThreadEffect(String threadKey, String effect) {
    }

    /** 一拍携带的知识效果证据。 */
    public record KnowledgeEffect(String kind, String key) {
    }

    /** 模型输出的结构化闭合动作。 */
    public record ClosingAct(String act, String targetKey, String tag) {
    }

    /** 模型输出的闭合证据。 */
    public record ClosureEvidence(String obligationKey, int chapterNo, String act,
                                  String eventKey, String tag) {

        public ClosureEvidence(String obligationKey, int chapterNo, String act, String eventKey) {
            this(obligationKey, chapterNo, act, eventKey, "");
        }
    }

    /** 窗口内状态变更。 */
    public record StateChange(String entityKey, String kind, String value) {
    }

    /** 窗口内知识读写事件。 */
    public record KnowledgeEvent(String kind, String key) {
    }

    /** 返回结构化问题；空列表表示窗口通过。 */
    public List<PlanContracts.PlanIssue> validate(DraftWindow window,
                                                  List<StoryObligation> obligations) {
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        if (window == null) {
            return issues;
        }
        List<StoryObligation> rows = obligations == null ? List.of() : obligations;
        StoryEvidenceIndex evidenceIndex = toEvidenceIndex(window);
        Map<Integer, ChapterDraft> chapterByNo = new HashMap<>();
        for (ChapterDraft chapter : window.chapters()) {
            chapterByNo.put(chapter.chapterNo(), chapter);
        }

        validateRequired(window, chapterByNo, rows, evidenceIndex, issues);
        validateClueActs(window, chapterByNo, rows, issues);
        validateEvidenceTiming(rows, evidenceIndex, issues);
        validateCharacterSetup(rows, evidenceIndex, issues);
        validateStateChanges(window, issues);
        validateKnowledge(window, issues);
        validateScope(window, issues);
        return List.copyOf(issues);
    }

    /** 从窗口结构化输出构建义务证据索引；不使用正文字符串包含。 */
    public StoryEvidenceIndex toEvidenceIndex(DraftWindow window) {
        StoryEvidenceIndex.Builder builder = StoryEvidenceIndex.builder();
        if (window == null) {
            return builder.build();
        }
        Map<String, StoryObligation> obligations = new LinkedHashMap<>();
        for (ChapterDraft chapter : window.chapters()) {
            String chapterEventKey = "CH" + chapter.chapterNo() + ":CHAPTER";
            List<Map<String, Object>> threadEffects = new ArrayList<>();
            int clueIndex = 0;
            for (String act : chapter.clueActs()) {
                clueIndex++;
                int split = act.indexOf(':');
                if (split <= 0) {
                    continue;
                }
                String kind = act.substring(0, split).toUpperCase(java.util.Locale.ROOT);
                String target = act.substring(split + 1);
                if (target.isBlank()) {
                    continue;
                }
                String eventKey = chapterEventKey + ":CLUE:" + clueIndex;
                builder.addEvent(new StoryEvent(eventKey, chapter.chapterNo(), "CLUE_ACT",
                        List.of(), chapter.entityKeys(), act, act, List.of(), List.of(),
                        List.of(Map.of("threadKey", target, "effect", kind)), "BATCH"));
                threadEffects.add(Map.of("threadKey", target, "effect", kind));
                String obligationKind = switch (kind) {
                    case "PLANT", "OPEN" -> "PLANT_THREAD";
                    case "PAYOFF", "CLOSE" -> "PAYOFF_THREAD";
                    case "CLOSURE", "REVEAL" -> "FINAL_CLOSURE";
                    default -> "";
                };
                if (!obligationKind.isBlank()) {
                    obligations.putIfAbsent(StoryObligation.obligationKey(
                            chapter.chapterNo(), obligationKind, target), new StoryObligation(
                            StoryObligation.obligationKey(chapter.chapterNo(),
                                    obligationKind, target), chapter.chapterNo(),
                            obligationKind, target, true, false, "HARD", "BATCH", Map.of()));
                    builder.addObligationEvidence(evidence(StoryObligation.obligationKey(
                                    chapter.chapterNo(), obligationKind, target),
                            chapter.chapterNo(), "CLUE_ACT", eventKey, null,
                            chapter.entityKeys(), List.of(target), act));
                }
            }
            int romanceIndex = 0;
            for (RomanceEvent event : chapter.romanceEvents()) {
                romanceIndex++;
                String stage = event.stage() == null
                        ? "" : event.stage().toUpperCase(java.util.Locale.ROOT);
                String obligationKind = switch (stage) {
                    case "RELATIONSHIP_START" -> "ADVANCE_THREAD";
                    case "MISUNDERSTANDING" -> "CHARACTER_CHOICE";
                    case "ATTACHMENT_ESCALATION" -> "CHANGE_STATE";
                    case "CONFLICT_PEAK" -> "CHARACTER_COST";
                    case "CONFESSION" -> "PAYOFF_THREAD";
                    case "RECONCILIATION" -> "LOCAL_CLOSURE";
                    case "RELATIONSHIP_CLOSURE" -> "FINAL_CLOSURE";
                    default -> "";
                };
                if (obligationKind.isBlank()) {
                    continue;
                }
                String target = "MISUNDERSTANDING".equals(stage) || "CONFLICT_PEAK".equals(stage)
                        ? event.targetKey() : event.threadKey();
                if (target == null || target.isBlank()) {
                    continue;
                }
                String eventKey = chapterEventKey + ":ROMANCE:" + romanceIndex;
                builder.addEvent(new StoryEvent(eventKey, chapter.chapterNo(), "ROMANCE_EVENT",
                        chapter.entityKeys(), List.of(target), event.evidence(), event.evidence(),
                        List.of(), List.of(), List.of(Map.of("threadKey", event.threadKey(),
                        "effect", stage)), "BATCH"));
                builder.addObligationEvidence(evidence(
                        StoryObligation.obligationKey(chapter.chapterNo(), obligationKind, target),
                        chapter.chapterNo(), "ROMANCE_EVENT", eventKey, null,
                        chapter.entityKeys(), List.of(event.threadKey()), event.evidence()));
            }
            builder.addEvent(new StoryEvent(chapterEventKey, chapter.chapterNo(),
                    "CHAPTER_OUTLINE", chapter.entityKeys(), List.of(), chapter.outline(),
                    chapter.outline(), List.of(), List.of(), threadEffects, "BATCH"));
            for (BeatDraft beat : window.beats()) {
                if (beat.chapterNo() != chapter.chapterNo()) {
                    continue;
                }
                addBeatEvidence(builder, chapter, beat);
            }
        }
        return builder.build();
    }

    private void addBeatEvidence(StoryEvidenceIndex.Builder builder, ChapterDraft chapter,
                                 BeatDraft beat) {
        String eventKey = "CH" + chapter.chapterNo() + ":BEAT:" + beat.seq();
        List<Map<String, Object>> threadEffects = new ArrayList<>();
        for (ThreadEffect effect : beat.threadEffects()) {
            if (effect.threadKey() == null || effect.threadKey().isBlank()) {
                continue;
            }
            threadEffects.add(Map.of("threadKey", effect.threadKey(),
                    "effect", effect.effect() == null ? "" : effect.effect()));
        }
        builder.addEvent(new StoryEvent(eventKey, beat.chapterNo(), "BEAT", beat.actorKeys(),
                beat.targetKeys(), beat.filled(), beat.filled(), List.of(), List.of(),
                threadEffects, "BATCH"));
        String beatRole = beat.role() == null ? "" : beat.role().toUpperCase(java.util.Locale.ROOT);
        if ("CLUE_PLANT".equals(beatRole) || "PLANT".equals(beatRole)) {
            addThreadEvidence(builder, beat.threadRefs(), chapter.chapterNo(),
                    "PLANT_THREAD", "BEAT", eventKey, beat.actorKeys(), beat.filled());
        }
        if ("CLUE_PAYOFF".equals(beatRole) || "PAYOFF".equals(beatRole)) {
            addThreadEvidence(builder, beat.threadRefs(), chapter.chapterNo(),
                    "PAYOFF_THREAD", "BEAT", eventKey, beat.actorKeys(), beat.filled());
        }
        for (ThreadEffect effect : beat.threadEffects()) {
            String normalized = effect.effect() == null
                    ? "" : effect.effect().toUpperCase(java.util.Locale.ROOT);
            String kind = switch (normalized) {
                case "OPEN", "PLANT" -> "PLANT_THREAD";
                case "PAYOFF", "CLOSE", "REVEAL" -> "PAYOFF_THREAD";
                default -> "";
            };
            if (!kind.isBlank() && effect.threadKey() != null) {
                addThreadEvidence(builder, List.of(effect.threadKey()), beat.chapterNo(),
                        kind, "THREAD_EFFECT", eventKey, beat.actorKeys(), beat.filled());
            }
        }
    }

    private void addThreadEvidence(StoryEvidenceIndex.Builder builder, List<String> threadKeys,
                                   int chapterNo, String kind, String evidenceKind,
                                   String eventKey, List<String> entityKeys, String fact) {
        for (String target : threadKeys) {
            if (target == null || target.isBlank()) {
                continue;
            }
            builder.addObligationEvidence(evidence(
                    StoryObligation.obligationKey(chapterNo, kind, target), chapterNo,
                    evidenceKind, eventKey, null, entityKeys, List.of(target), fact));
        }
    }

    private static StoryObligationEvidence evidence(String obligationKey, int chapterNo,
                                                     String evidenceKind, String eventKey,
                                                     Integer beatSeq, List<String> entityKeys,
                                                     List<String> threadKeys, String fact) {
        return new StoryObligationEvidence(obligationKey, chapterNo, evidenceKind, eventKey,
                beatSeq, entityKeys, threadKeys, fact, "BATCH", 1.0);
    }

    private void validateRequired(DraftWindow window, Map<Integer, ChapterDraft> chapterByNo,
                                  List<StoryObligation> obligations,
                                  StoryEvidenceIndex evidenceIndex,
                                  List<PlanContracts.PlanIssue> issues) {
        for (StoryObligation obligation : obligations) {
            if (!obligation.required() && !obligation.forbidden()) {
                continue;
            }
            if (obligation.chapterNo() < window.fromChapter()
                    || obligation.chapterNo() > window.toChapter()) {
                continue;
            }
            ChapterDraft chapter = chapterByNo.get(obligation.chapterNo());
            if (obligation.forbidden()) {
                boolean violated = chapter != null && (chapter.entityKeys().contains(obligation.targetKey())
                        || chapter.threadKeys().contains(obligation.targetKey())
                        || chapter.clueActs().stream().anyMatch(
                                act -> act.endsWith(":" + obligation.targetKey())));
                if (violated) {
                    issues.add(issue("OBLIGATION_VIOLATED", obligation.chapterNo(),
                            "窗口违反禁令义务 " + obligation.obligationKey()));
                }
                continue;
            }
            boolean fulfilled = evidenceIndex.hasEvidence(obligation.obligationKey());
            if (!fulfilled) {
                issues.add(issue("OBLIGATION_MISSING", obligation.chapterNo(),
                        "窗口缺少义务 " + obligation.obligationKey()));
            }
        }
    }

    private void validateEvidenceTiming(List<StoryObligation> obligations,
                                        StoryEvidenceIndex evidenceIndex,
                                        List<PlanContracts.PlanIssue> issues) {
        Map<String, Integer> requiredPayoff = new HashMap<>();
        for (StoryObligation obligation : obligations) {
            if ("PAYOFF_THREAD".equals(obligation.kind())) {
                requiredPayoff.put(obligation.targetKey(), obligation.chapterNo());
            }
        }
        Map<String, Integer> payoffCount = new HashMap<>();
        for (List<StoryObligationEvidence> rows : evidenceIndex.obligationsByChapter().values()) {
            for (StoryObligationEvidence evidence : rows) {
                if (!"PAYOFF_THREAD".equals(kindOf(evidence.obligationKey()))) {
                    continue;
                }
                String target = targetOf(evidence.obligationKey());
                int count = payoffCount.merge(target, 1, Integer::sum);
                if (count > 1) {
                    issues.add(issue("OBLIGATION_VIOLATED", evidence.chapterNo(),
                            "线索 " + target + " 在本窗重复兑现"));
                }
                Integer required = requiredPayoff.get(target);
                if (required != null && evidence.chapterNo() < required) {
                    issues.add(issue("OBLIGATION_EARLY", evidence.chapterNo(),
                            "义务 PAYOFF_THREAD:" + target + " 要求第 " + required + " 章"));
                }
            }
        }
    }

    private void validateCharacterSetup(List<StoryObligation> obligations,
                                        StoryEvidenceIndex evidenceIndex,
                                        List<PlanContracts.PlanIssue> issues) {
        for (StoryObligation cost : obligations) {
            if (!"CHARACTER_COST".equals(cost.kind())) {
                continue;
            }
            boolean setupScoped = obligations.stream().anyMatch(row ->
                    "INTRODUCE_ENTITY".equals(row.kind())
                            && row.targetKey().equals(cost.targetKey())
                            && row.chapterNo() == asInt(cost.payload().get("afterChapter"),
                            cost.chapterNo()));
            boolean choiceScoped = obligations.stream().anyMatch(row ->
                    "CHARACTER_CHOICE".equals(row.kind())
                            && row.targetKey().equals(cost.targetKey()));
            if (!setupScoped || !choiceScoped) {
                continue;
            }
            String setupKey = StoryObligation.obligationKey(
                    asInt(cost.payload().get("afterChapter"), cost.chapterNo()),
                    "INTRODUCE_ENTITY", cost.targetKey());
            String choiceKey = StoryObligation.obligationKey(
                    asInt(cost.payload().get("afterChapter"), cost.chapterNo()),
                    "CHARACTER_CHOICE", cost.targetKey());
            if (!evidenceIndex.hasEvidence(setupKey) || !evidenceIndex.hasEvidence(choiceKey)) {
                issues.add(issue("OBLIGATION_MISSING_SETUP", cost.chapterNo(),
                        "角色 " + cost.targetKey() + " 的代价义务缺少 setup/choice 证据"));
            }
        }
    }

    private static String kindOf(String obligationKey) {
        int first = obligationKey == null ? -1 : obligationKey.indexOf(':');
        int second = obligationKey == null ? -1 : obligationKey.indexOf(':', first + 1);
        return first < 0 || second < 0 ? "" : obligationKey.substring(first + 1, second);
    }

    private static String targetOf(String obligationKey) {
        int second = obligationKey == null ? -1 : obligationKey.indexOf(':',
                obligationKey.indexOf(':') + 1);
        return second < 0 || second + 1 >= obligationKey.length()
                ? "" : obligationKey.substring(second + 1);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private void validateClueActs(DraftWindow window, Map<Integer, ChapterDraft> chapterByNo,
                                  List<StoryObligation> obligations,
                                  List<PlanContracts.PlanIssue> issues) {
        Map<String, Integer> payoffCount = new HashMap<>();
        Map<String, Integer> requiredPlant = new HashMap<>();
        Map<String, Integer> requiredPayoff = new HashMap<>();
        for (StoryObligation obligation : obligations) {
            if ("PLANT_THREAD".equals(obligation.kind())) {
                requiredPlant.put(obligation.targetKey(), obligation.chapterNo());
            } else if ("PAYOFF_THREAD".equals(obligation.kind())) {
                requiredPayoff.put(obligation.targetKey(), obligation.chapterNo());
            }
        }
        for (ChapterDraft chapter : window.chapters()) {
            for (String act : chapter.clueActs()) {
                int split = act.indexOf(':');
                if (split <= 0) {
                    continue;
                }
                String kind = act.substring(0, split);
                String target = act.substring(split + 1);
                Integer required = "PAYOFF".equals(kind) ? requiredPayoff.get(target)
                        : requiredPlant.get(target);
                if (required != null && chapter.chapterNo() != required) {
                    issues.add(issue("OBLIGATION_EARLY", chapter.chapterNo(),
                            "义务 " + kind + ":" + target + " 要求第 " + required + " 章"));
                }
                if ("PAYOFF".equals(kind)) {
                    int count = payoffCount.merge(target, 1, Integer::sum);
                    if (count > 1) {
                        issues.add(issue("OBLIGATION_VIOLATED", chapter.chapterNo(),
                                "线索 " + target + " 在本窗重复兑现"));
                    }
                }
            }
        }
    }

    private void validateStateChanges(DraftWindow window, List<PlanContracts.PlanIssue> issues) {
        for (Map.Entry<Integer, List<StateChange>> entry : window.stateChanges().entrySet()) {
            Map<String, String> seen = new HashMap<>();
            for (StateChange change : entry.getValue()) {
                String previous = seen.putIfAbsent(change.entityKey() + "|" + change.kind(),
                        change.value());
                if (previous != null && !previous.equals(change.value())) {
                    issues.add(issue("STATE_CONFLICT", entry.getKey(),
                            "实体 " + change.entityKey() + " 的 " + change.kind()
                                    + " 在同一窗出现冲突值"));
                }
            }
        }
    }

    private void validateKnowledge(DraftWindow window, List<PlanContracts.PlanIssue> issues) {
        Set<String> written = new HashSet<>();
        for (int chapterNo = window.fromChapter(); chapterNo <= window.toChapter(); chapterNo++) {
            List<KnowledgeEvent> events = window.knowledgeEvents().getOrDefault(chapterNo, List.of());
            for (KnowledgeEvent event : events) {
                if ("WRITE".equalsIgnoreCase(event.kind())) {
                    written.add(event.key());
                } else if ("READ".equalsIgnoreCase(event.kind()) && !written.contains(event.key())) {
                    issues.add(issue("KNOWLEDGE_SOURCE_MISSING", chapterNo,
                            "知识 " + event.key() + " 被读取但此前没有写入来源"));
                }
            }
        }
    }

    private void validateScope(DraftWindow window, List<PlanContracts.PlanIssue> issues) {
        Set<String> allowed = window.allowedEntityKeys();
        if (allowed.isEmpty()) {
            return;
        }
        for (ChapterDraft chapter : window.chapters()) {
            for (String key : chapter.entityKeys()) {
                if (!allowed.contains(key)) {
                    issues.add(issue("CONTRACT_OUT_OF_SCOPE", chapter.chapterNo(),
                            "合同外实体 " + key));
                }
            }
        }
        for (BeatDraft beat : window.beats()) {
            for (String key : concat(beat.actorKeys(), beat.targetKeys())) {
                if (!allowed.contains(key)) {
                    issues.add(issue("CONTRACT_OUT_OF_SCOPE", beat.chapterNo(),
                            "拍 " + beat.seq() + " 引用合同外实体 " + key));
                }
            }
        }
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>(left);
        out.addAll(right);
        return out;
    }

    private static PlanContracts.PlanIssue issue(String code, Integer chapterNo, String message) {
        return new PlanContracts.PlanIssue("HARD", code, PlanContracts.RepairScope.DRAFT,
                null, chapterNo, List.of(), message);
    }
}
