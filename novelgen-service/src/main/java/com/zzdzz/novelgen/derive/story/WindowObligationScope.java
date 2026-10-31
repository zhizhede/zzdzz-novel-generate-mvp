package com.zzdzz.novelgen.derive.story;

import java.util.ArrayList;
import java.util.List;

/**
 * 窗口义务作用域：当前窗校验、未来窗递延、已过窗未兑现单独暴露。
 */
public record WindowObligationScope(
        int windowFrom,
        int windowTo,
        List<StoryObligation> current,
        List<StoryObligation> deferred,
        List<StoryObligation> future) {

    public WindowObligationScope {
        current = current == null ? List.of() : List.copyOf(current);
        deferred = deferred == null ? List.of() : List.copyOf(deferred);
        future = future == null ? List.of() : List.copyOf(future);
    }

    /** 按窗口切义务；windowTo 小于 windowFrom 时全部视为未来。 */
    public static WindowObligationScope of(int windowFrom, int windowTo,
                                           List<StoryObligation> obligations) {
        List<StoryObligation> rows = obligations == null ? List.of() : obligations;
        List<StoryObligation> current = new ArrayList<>();
        List<StoryObligation> deferred = new ArrayList<>();
        List<StoryObligation> pastDue = new ArrayList<>();
        for (StoryObligation obligation : rows) {
            if (obligation.chapterNo() >= windowFrom && obligation.chapterNo() <= windowTo) {
                current.add(obligation);
            } else if (obligation.chapterNo() > windowTo) {
                deferred.add(obligation);
            } else {
                pastDue.add(obligation);
            }
        }
        return new WindowObligationScope(windowFrom, windowTo, current, deferred, pastDue);
    }
}
