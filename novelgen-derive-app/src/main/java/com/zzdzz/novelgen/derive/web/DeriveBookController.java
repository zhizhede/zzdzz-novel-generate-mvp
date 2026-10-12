package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.derive.service.DeriveBookService;
import com.zzdzz.novelgen.derive.service.DeriveExtractService;
import com.zzdzz.novelgen.model.dto.derive.BookIngestDTO;
import com.zzdzz.novelgen.model.vo.derive.BookIngestVO;
import com.zzdzz.novelgen.model.vo.derive.BookVO;
import com.zzdzz.novelgen.model.vo.derive.ExtractVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ①取样入库 + ②结构抽取 REST（均需登录，/api/** 拦截）。 */
@RestController
@RequestMapping("/api/derive/books")
@RequiredArgsConstructor
public class DeriveBookController {

    private final DeriveBookService bookService;
    private final DeriveExtractService extractService;

    @PostMapping
    public Result<BookIngestVO> ingest(@RequestBody BookIngestDTO dto) {
        return Result.success(bookService.ingest(dto));
    }

    @GetMapping
    public Result<List<BookVO>> list() {
        return Result.success(bookService.list());
    }

    /** ②结构抽取（同步 LLM 调用，数秒~分钟级）。 */
    @PostMapping("/{id}/extract")
    public Result<ExtractVO> extract(@PathVariable long id) {
        return Result.success(extractService.extract(id));
    }
}
