package com.zzdzz.novelgen.model.vo;

/** 品类语料章列表行（不含正文大字段）。 */
public record PresetCorpusVO(long id, String genre, String title, int wordCount) {

    public static PresetCorpusVO from(com.zzdzz.novelgen.model.dto.PresetCorpusDTO d) {
        return new PresetCorpusVO(d.getId(), d.getGenre(), d.getTitle(),
                d.getWordCount() == null ? 0 : d.getWordCount());
    }
}
