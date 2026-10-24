package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.derive.service.DeriveBookService;
import com.zzdzz.novelgen.derive.service.DeriveDeindexService;
import com.zzdzz.novelgen.derive.service.DeriveDomainService;
import com.zzdzz.novelgen.derive.service.DeriveExtractService;
import com.zzdzz.novelgen.derive.service.DeriveLabelingService;
import com.zzdzz.novelgen.derive.service.DeriveQueryService;
import com.zzdzz.novelgen.derive.service.DeriveStyleService;
import com.zzdzz.novelgen.model.dto.derive.BookIngestDTO;
import com.zzdzz.novelgen.model.dto.derive.BookQueryDTO;
import com.zzdzz.novelgen.model.vo.derive.BookDetailVO;
import com.zzdzz.novelgen.model.vo.derive.BookIngestVO;
import com.zzdzz.novelgen.model.vo.derive.BookVO;
import com.zzdzz.novelgen.model.vo.derive.DeindexVO;
import com.zzdzz.novelgen.model.vo.derive.DomainVO;
import com.zzdzz.novelgen.model.vo.derive.ExtractVO;
import com.zzdzz.novelgen.model.vo.derive.GrepVO;
import com.zzdzz.novelgen.model.vo.derive.LabelVO;
import com.zzdzz.novelgen.model.vo.derive.SourceChapterVO;
import com.zzdzz.novelgen.model.vo.derive.StyleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ①取样入库 + ②结构抽取 + ③无指向化 REST（均需登录，/api/** 拦截）+ 书读端点（详情/源章/筛选列表）。 */
@RestController
@RequestMapping("/api/derive/books")
@RequiredArgsConstructor
public class DeriveBookController {

    private final DeriveBookService bookService;
    private final DeriveExtractService extractService;
    private final DeriveDeindexService deindexService;
    private final DeriveLabelingService labelingService;
    private final DeriveDomainService domainService;
    private final DeriveStyleService styleService;
    private final DeriveQueryService queryService;

    @PostMapping
    public Result<BookIngestVO> ingest(@RequestBody BookIngestDTO dto) {
        return Result.success(bookService.ingest(dto));
    }

    /** 书列表（带筛选：keyword/sourceType/from/to/sort；缺省=SOURCE 且 id 倒序，行为同旧版）。 */
    @GetMapping
    public Result<List<BookVO>> list(BookQueryDTO condition) {
        return Result.success(queryService.listBooks(condition));
    }

    /** 书详情：元数据 + 素材处理进度读数（源章/弧/拍/世界/标签计数、文风卡有无）。 */
    @GetMapping("/{id}")
    public Result<BookDetailVO> get(@PathVariable long id) {
        return Result.success(queryService.getBook(id));
    }

    /** 源章列表（seq 升序；includeText=true 附原文）。 */
    @GetMapping("/{id}/chapters")
    public Result<List<SourceChapterVO>> chapters(@PathVariable long id,
                                                  @RequestParam(defaultValue = "false") boolean includeText) {
        return Result.success(queryService.listSourceChapters(id, includeText));
    }

    /** ②结构抽取（同步 LLM 调用，数秒~分钟级）。 */
    @PostMapping("/{id}/extract")
    public Result<ExtractVO> extract(@PathVariable long id) {
        return Result.success(extractService.extract(id));
    }

    /** ③无指向化（2 次 LLM 调用：批量清洗 + 源词表）。 */
    @PostMapping("/{id}/deindex")
    public Result<DeindexVO> deindex(@PathVariable long id) {
        return Result.success(deindexService.deindex(id));
    }

    /** ④a 黑名单/领域词 grep（脚本侧，命中→打回③；id=-1 = 全池）。 */
    @PostMapping("/{id}/grep")
    public Result<GrepVO> grep(@PathVariable long id) {
        return Result.success(labelingService.grep(id));
    }

    /** ④b 批量打标（LLM，标签只进 label_index）。 */
    @PostMapping("/{id}/label")
    public Result<LabelVO> label(@PathVariable long id) {
        return Result.success(labelingService.label(id));
    }

    /** ④c 域格归纳（LLM → domain_cell，同 grp+name 幂等跳过）。 */
    @PostMapping("/{id}/domains")
    public Result<DomainVO> domains(@PathVariable long id) {
        return Result.success(domainService.domains(id));
    }

    /** ④d 文风抽取（可选步，LLM → story_settings.style_profile；覆盖式幂等）。 */
    @PostMapping("/{id}/style")
    public Result<StyleVO> style(@PathVariable long id) {
        return Result.success(styleService.extract(id));
    }
}
