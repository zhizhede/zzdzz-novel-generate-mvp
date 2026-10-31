package com.zzdzz.novelgen.derive.story;

/**
 * 通用故事约束：限定某个作用域和章节区间内禁止或必须满足的规则。
 */
public record StoryConstraint(
        String constraintKey,
        String scope,
        Integer chapterFrom,
        Integer chapterTo,
        String kind,
        String targetKey,
        String message) {

    public StoryConstraint {
        constraintKey = StoryEntity.requireText(constraintKey, "constraintKey");
        scope = StoryEntity.requireText(scope, "scope");
        kind = StoryEntity.requireText(kind, "kind");
        if (chapterFrom != null && chapterFrom <= 0) {
            throw new IllegalArgumentException("chapterFrom 必须为正数");
        }
        if (chapterTo != null && chapterTo <= 0) {
            throw new IllegalArgumentException("chapterTo 必须为正数");
        }
        if (chapterFrom != null && chapterTo != null && chapterFrom > chapterTo) {
            throw new IllegalArgumentException("chapterFrom 不能大于 chapterTo");
        }
    }
}
