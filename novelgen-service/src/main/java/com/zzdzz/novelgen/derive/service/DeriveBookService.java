package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.SourceChapterMapper;
import com.zzdzz.novelgen.model.dto.derive.BookIngestDTO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.SourceChapterDO;
import com.zzdzz.novelgen.model.vo.derive.BookIngestVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ①取样入库：正文切章 → novels(SOURCE) + source_chapter。
 *
 * <p>切章口径对齐 zzdzz（TextSplitter 头注）；事务只包短写（建书+落章），无 LLM 调用。
 * jsonb 写入走 NovelMapper.insertFull（MP save 会被 PG 拒隐式转）。
 * 列表/详情等读端点在 {@link DeriveQueryService}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeriveBookService {

    /** 同 zzdzz IMPORT_MAX_CHARS：800 万字正文闸。 */
    private static final int MAX_TEXT_CHARS = 8_000_000;

    private final NovelMapper novelMapper;
    private final SourceChapterMapper sourceChapterMapper;

    /** 取样入库（①）。 */
    @Transactional
    public BookIngestVO ingest(BookIngestDTO dto) {
        if (dto == null || dto.title() == null || dto.title().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名不能为空");
        }
        String text = dto.text();
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "正文不能为空（请粘贴全文）");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "正文超长（" + text.length() / 10_000_000 + " 千万字 > 上限 800 万），请分段导入");
        }
        List<TextSplitter.ChapterSlice> slices = new ArrayList<>(TextSplitter.split(text));
        List<String> notes = new ArrayList<>();
        if (slices.size() == 1) {
            notes.add("未识别到章标题——整篇已作为第 1 章入库（支持的标题行：第N章 / 第一章 / 一、标题）");
        }
        boolean renumbered = TextSplitter.renumber(slices);
        if (renumbered) {
            notes.add("原章号不连续（或未从 1 开始）——已按出现顺序重排为 1.." + slices.size());
        }

        NovelDO novel = new NovelDO();
        novel.setTitle(dto.title().strip());
        novel.setSourceType("SOURCE");
        novel.setCategoryTags(blankToNull(dto.categoryTags()));
        novel.setAuthor(blankToNull(dto.author()));
        novel.setPlatform(blankToNull(dto.platform()));
        long novelId = novelMapper.insertFull(novel);

        int totalChars = 0;
        for (TextSplitter.ChapterSlice s : slices) {
            SourceChapterDO row = new SourceChapterDO();
            row.setNovelId(novelId);
            row.setSeq(s.no());
            row.setTitle(s.title());
            row.setContent(s.content());
            row.setCharCount(s.content().length());
            sourceChapterMapper.insert(row);
            totalChars += s.content().length();
        }

        // 回填全书字数/章数（MP NOT_NULL 策略只更新显式设置的列；updated_time 显式刷新）
        NovelDO backfill = new NovelDO();
        backfill.setId(novelId);
        backfill.setChapterCount(slices.size());
        backfill.setCharCount(totalChars);
        backfill.setUpdatedTime(OffsetDateTime.now());
        novelMapper.updateById(backfill);

        log.info("取样入库：novelId={} title={} 章数={} 字数={} 重排={}",
                novelId, novel.getTitle(), slices.size(), totalChars, renumbered);
        return new BookIngestVO(novelId, novel.getTitle(), slices.size(), totalChars, renumbered, notes);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
