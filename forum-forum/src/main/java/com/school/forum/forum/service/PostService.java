package com.school.forum.forum.service;

import com.school.forum.common.result.PageResult;
import com.school.forum.forum.dto.PostCreateRequest;
import com.school.forum.forum.entity.Post;
import com.school.forum.forum.vo.PostDetailVO;
import com.school.forum.forum.vo.PostVO;

/**
 * 帖子：发布、列表、详情。
 */
public interface PostService {

    /**
     * 发帖。
     *
     * @param idempotencyKey 请求头 {@code Idempotency-Key}，可为 null。
     *                       同一个 key 的重复提交返回首次创建的帖子 ID，不会重复发帖
     * @return 新帖（或已存在帖子）的 ID
     */
    Long create(Long userId, String idempotencyKey, PostCreateRequest request);

    /**
     * 帖子列表。
     *
     * @param boardId       板块 ID。传 null 表示跨板块的全站信息流（首页）
     * @param sort          排序方式，见 {@link com.school.forum.forum.support.PostSort}，非法值退回 latest
     * @param cursor        上一页返回的 nextCursor，首页传 null
     * @param size          每页条数，超出范围会被收敛到 [1, 50]
     * @param currentUserId 当前登录用户，未登录传 null（列表里的 isLiked 全为 false）
     */
    PageResult<PostVO> list(Long boardId, String sort, String cursor, int size, Long currentUserId);

    /**
     * 帖子详情。会顺带记一次浏览（同一用户 30 分钟内只记一次）。
     *
     * @throws com.school.forum.common.exception.BizException 帖子不存在或不可见
     */
    PostDetailVO detail(Long postId, Long currentUserId);

    /**
     * 取一条已发布的帖子，不存在则抛异常。
     *
     * <p>供评论服务校验「帖子还在不在、是不是楼主」使用。直接依赖本接口而不是
     * 让评论服务自己查 {@code PostMapper}：两者共享同一套「什么算可见」的判断，
     * 分散在两处迟早会出现「详情页看不到、却能评论」这种不一致。
     */
    Post requirePublished(Long postId);
}
