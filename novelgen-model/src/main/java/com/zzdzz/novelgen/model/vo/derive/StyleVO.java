package com.zzdzz.novelgen.model.vo.derive;

import com.fasterxml.jackson.databind.JsonNode;

/** ④d 文风抽取结果（文风卡画像 + 本次实际读入的正文字数）。 */
public record StyleVO(
        long novelId,
        int inputChars,
        JsonNode styleProfile
) {
}
