package com.zzdzz.novelgen.model.vo;

import java.util.List;

/** 章纲全景行：场景拆解 + 所属章（章号/章题），章纲 tab 全景展示与筛选用。 */
public record ChapterSceneVO(int chapterNo, String chapterTitle, int sceneNo, String goal,
                             List<String> present, List<String> mustReveal, List<String> mustNot,
                             int wordsBudget) {
}
