package com.school.forum.forum.controller;

import com.school.forum.common.result.Result;
import com.school.forum.forum.service.BoardService;
import com.school.forum.forum.vo.BoardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 板块接口。
 */
@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    /** 板块列表。无需登录——发帖前也要能看到有哪些板块 */
    @GetMapping
    public Result<List<BoardVO>> list() {
        return Result.ok(boardService.list());
    }
}
