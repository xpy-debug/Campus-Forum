package com.school.forum.forum.service;

import com.school.forum.forum.entity.Board;
import com.school.forum.forum.vo.BoardVO;

import java.util.List;

/**
 * 板块。
 */
public interface BoardService {

    /**
     * 板块列表，按权重升序。
     *
     * <p><b>不加缓存。</b>板块表只有个位数的行，一次主键顺序扫描的代价
     * 比一次 Redis 往返还低，加缓存反而要多维护一套失效逻辑。
     */
    List<BoardVO> list();

    /**
     * 校验板块可发帖，并返回板块本身。
     *
     * <p>返回实体而不是 ID，是因为发帖还要读它的 {@code postAudit}
     * （决定帖子是直接发布还是进审核队列）。若只返回 ID，
     * 调用方紧接着又要查一次同一行。
     *
     * @throws com.school.forum.common.exception.BizException 板块不存在或已关闭
     */
    Board requirePostable(Long boardId);
}
