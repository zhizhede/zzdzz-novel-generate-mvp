package com.zzdzz.novelgen.model.vo;

/**
 * 规划资产查询条件（URL 查询参数绑定，全可空 = 不筛）。
 *
 * level 取 OUTLINE（大纲，一本书一行）/ VOLUME（卷纲，一书一卷一行）/ CHAPTER（章纲，一章一行），缺省 CHAPTER。
 * hasOutline / hasText / skeleton 取 YES / NO / ALL：
 *  - hasOutline：大纲层=有没有大纲文档；卷纲层=卷内有没有章纲；章纲层=本章有没有章纲；
 *  - hasText：卷纲层=卷内有没有正文；章纲层=本章有没有正文（大纲层无正文概念，该条件不生效）；
 *  - skeleton：只看仍是样本剧情骨架（未改写）的大纲（仅大纲层生效）。
 * status：章状态精确匹配，**仅章纲层生效**（卷是若干章的集合，单值状态对它无意义）。
 * minChars / maxChars：大纲层按大纲字数，卷纲/章纲层按正文字数。
 * volumeNo：0 = 只筛「未分卷」（volume_no 为空的导入章）；其余按卷号精确匹配。
 * fromChapter / toChapter：章纲层按章号；卷纲层按卷的章号区间**相交**判定；大纲层不生效。
 * from / to 为 yyyy-MM-dd（含当日），按各行的创建时间（卷纲行取卷内最新）。
 * sort 取 ORDER_ASC（默认，书 + 卷号 + 章号）/ ORDER_DESC / TIME_DESC / TIME_ASC / TEXT_DESC / OUTLINE_DESC / TITLE_ASC。
 */
public record PlanAssetQueryVO(
        String level,
        Long novelId,
        String sourceType,
        String keyword,
        Integer volumeNo,
        Integer fromChapter,
        Integer toChapter,
        String status,
        String hasOutline,
        String hasText,
        String skeleton,
        Long minChars,
        Long maxChars,
        String from,
        String to,
        String sort) {
}
