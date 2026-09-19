package com.school.forum.forum.service;

import com.school.forum.common.result.PageResult;
import com.school.forum.forum.dto.CommentCreateRequest;
import com.school.forum.forum.vo.CommentVO;

/**
 * 评论：发表、列表、删除。
 *
 * <p>两级模型：一级评论按楼层排列，二级评论挂在某个一级评论下，深度不再增加。
 * 想回复二级评论时，新评论仍然挂到同一个一级评论下（{@code parentId} 指向被回复的那条，
 * 只用于渲染「回复 @某某」），因此不存在第三级。
 */
public interface CommentService {

    /**
     * 发表评论或回复。
     *
     * @param userId 评论者
     */
    CommentVO create(Long userId, CommentCreateRequest request);

    /**
     * 某帖的一级评论分页，每条一级评论附带前几条二级评论预览。
     *
     * @param cursor 上一页的 nextCursor，首页传 null
     */
    PageResult<CommentVO> list(Long postId, String cursor, int size, Long currentUserId);

    /**
     * 删除评论（软删除）。作者本人、楼主、版主以上可删。
     *
     * @param operatorRole 操作者角色，取 {@code LoginUser.ROLE_*}，由调用方从登录态取出。
     *                     服务层收 int 而不是收 LoginUser：后者是 Web 层的类型，
     *                     让它渗进服务层会让「服务层不依赖 Web」这条边界失效
     */
    void delete(Long commentId, Long operatorId, int operatorRole);
}
