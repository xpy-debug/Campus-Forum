package com.school.forum.forum.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.forum.convert.BoardConverter;
import com.school.forum.forum.entity.Board;
import com.school.forum.forum.mapper.BoardMapper;
import com.school.forum.forum.service.BoardService;
import com.school.forum.forum.vo.BoardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 板块实现。
 */
@Service
@RequiredArgsConstructor
public class BoardServiceImpl implements BoardService {

    private final BoardMapper boardMapper;

    @Override
    public List<BoardVO> list() {
        // 隐藏板块（status=1）不出现在列表里，但仍可通过 ID 直达——
        // 这正是「隐藏」与「关闭」的区别，因此这里过滤的是 status 而不是全部
        List<Board> boards = boardMapper.selectList(Wrappers.<Board>lambdaQuery()
                .ne(Board::getStatus, Board.STATUS_HIDDEN)
                .orderByAsc(Board::getSortOrder)
                .orderByAsc(Board::getId));
        return boards.stream().map(BoardConverter::toVO).toList();
    }

    @Override
    public Board requirePostable(Long boardId) {
        Board board = boardId == null ? null : boardMapper.selectById(boardId);
        if (board == null) {
            throw new BizException(ErrorCode.BOARD_NOT_FOUND);
        }
        if (board.getStatus() != null && board.getStatus() == Board.STATUS_CLOSED) {
            throw new BizException(ErrorCode.BOARD_CLOSED);
        }
        return board;
    }
}
