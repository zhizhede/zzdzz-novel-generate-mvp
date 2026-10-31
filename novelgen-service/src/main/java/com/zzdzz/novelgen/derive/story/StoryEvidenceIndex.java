package com.zzdzz.novelgen.derive.story;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用故事证据索引：按章保存事件、义务证据和线程，供 ⑥b、P6、QC 复用同一套事实。
 */
public record StoryEvidenceIndex(
        Map<Integer, List<StoryEvent>> eventsByChapter,
        Map<Integer, List<StoryObligationEvidence>> obligationsByChapter,
        Map<Integer, List<StoryThread>> threadsByChapter,
        Map<String, List<StoryObligationEvidence>> evidenceByObligationKey) {

    public StoryEvidenceIndex {
        eventsByChapter = copyEvents(eventsByChapter);
        obligationsByChapter = copyEvidence(obligationsByChapter);
        threadsByChapter = copyThreads(threadsByChapter);
        evidenceByObligationKey = copyByObligation(obligationsByChapter);
    }

    /** 是否存在指定义务键的结构化证据。 */
    public boolean hasEvidence(String obligationKey) {
        return evidenceFor(obligationKey) != null && !evidenceFor(obligationKey).isEmpty();
    }

    /** 指定义务键的全部证据；无证据返回空列表。 */
    public List<StoryObligationEvidence> evidenceFor(String obligationKey) {
        return obligationKey == null ? List.of()
                : evidenceByObligationKey.getOrDefault(obligationKey, List.of());
    }

    /** 链式装配器；保持输入顺序并拒绝重复证据。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 证据索引装配器。 */
    public static final class Builder {
        private final Map<Integer, List<StoryEvent>> events = new LinkedHashMap<>();
        private final Map<Integer, List<StoryObligationEvidence>> obligations = new LinkedHashMap<>();
        private final Map<Integer, List<StoryThread>> threads = new LinkedHashMap<>();
        private final Map<String, StoryEvent> eventKeys = new LinkedHashMap<>();
        private final Map<String, StoryObligationEvidence> evidenceKeys = new LinkedHashMap<>();
        private final Map<String, StoryThread> threadKeys = new LinkedHashMap<>();

        public Builder addEvent(StoryEvent event) {
            if (event == null) {
                return this;
            }
            if (eventKeys.putIfAbsent(event.eventKey(), event) != null) {
                throw new IllegalArgumentException("事件 key 重复：" + event.eventKey());
            }
            events.computeIfAbsent(event.chapterNo(), key -> new ArrayList<>()).add(event);
            return this;
        }

        public Builder addObligationEvidence(StoryObligationEvidence evidence) {
            if (evidence == null) {
                return this;
            }
            if (evidenceKeys.putIfAbsent(evidence.evidenceKey(), evidence) != null) {
                throw new IllegalArgumentException("证据 key 重复：" + evidence.evidenceKey());
            }
            obligations.computeIfAbsent(evidence.chapterNo(), key -> new ArrayList<>())
                    .add(evidence);
            return this;
        }

        public Builder addThread(StoryThread thread) {
            if (thread == null) {
                return this;
            }
            if (threadKeys.putIfAbsent(thread.threadKey(), thread) != null) {
                throw new IllegalArgumentException("线程 key 重复：" + thread.threadKey());
            }
            int chapterNo = thread.openedChapter() == null ? 0 : thread.openedChapter();
            threads.computeIfAbsent(chapterNo, key -> new ArrayList<>()).add(thread);
            return this;
        }

        public StoryEvidenceIndex build() {
            return new StoryEvidenceIndex(events, obligations, threads, Map.of());
        }
    }

    private static Map<Integer, List<StoryEvent>> copyEvents(
            Map<Integer, List<StoryEvent>> source) {
        Map<Integer, List<StoryEvent>> out = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((chapterNo, rows) -> out.put(chapterNo,
                    List.copyOf(rows == null ? List.of() : rows)));
        }
        return Map.copyOf(out);
    }

    private static Map<Integer, List<StoryObligationEvidence>> copyEvidence(
            Map<Integer, List<StoryObligationEvidence>> source) {
        Map<Integer, List<StoryObligationEvidence>> out = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((chapterNo, rows) -> out.put(chapterNo,
                    List.copyOf(rows == null ? List.of() : rows)));
        }
        return Map.copyOf(out);
    }

    private static Map<Integer, List<StoryThread>> copyThreads(
            Map<Integer, List<StoryThread>> source) {
        Map<Integer, List<StoryThread>> out = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((chapterNo, rows) -> out.put(chapterNo,
                    List.copyOf(rows == null ? List.of() : rows)));
        }
        return Map.copyOf(out);
    }

    private static Map<String, List<StoryObligationEvidence>> copyByObligation(
            Map<Integer, List<StoryObligationEvidence>> source) {
        Map<String, List<StoryObligationEvidence>> out = new LinkedHashMap<>();
        if (source != null) {
            source.values().stream().flatMap(List::stream).forEach(evidence ->
                    out.computeIfAbsent(evidence.obligationKey(), key -> new ArrayList<>())
                            .add(evidence));
        }
        out.replaceAll((key, rows) -> List.copyOf(rows));
        return Map.copyOf(out);
    }
}
