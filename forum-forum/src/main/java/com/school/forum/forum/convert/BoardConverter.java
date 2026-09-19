package com.school.forum.forum.convert;

import com.school.forum.forum.entity.Board;
import com.school.forum.forum.vo.BoardVO;

/**
 * 板块实体到出参的转换。
 */
public final class BoardConverter {

    private BoardConverter() {
    }

    public static BoardVO toVO(Board board) {
        return new BoardVO(
                board.getId(),
                board.getName(),
                board.getCode(),
                board.getDescription(),
                board.getIcon(),
                value(board.getPostCount()),
                value(board.getTodayCount()),
                board.getStatus() == null ? Board.STATUS_NORMAL : board.getStatus(),
                board.getIsDefault() != null && board.getIsDefault() == 1);
    }

    /** 冗余计数列在极端情况下可能是 NULL（历史数据），转成 0 而不是让前端处理 null */
    private static long value(Integer count) {
        return count == null ? 0L : count;
    }
}
