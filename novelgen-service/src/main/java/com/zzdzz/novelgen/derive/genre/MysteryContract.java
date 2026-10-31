package com.zzdzz.novelgen.derive.genre;

import com.zzdzz.novelgen.derive.contract.StoryContract;

/** 悬疑类型合同：包装旧 StoryContract，保持旧 run 与旧管线兼容。 */
public record MysteryContract(StoryContract story) implements GenreContract {

    public MysteryContract {
        if (story == null) {
            throw new IllegalArgumentException("story 不能为空");
        }
    }
}
