package com.school.forum.forum.controller;

import com.school.forum.common.result.PageResult;
import com.school.forum.common.result.Result;
import com.school.forum.forum.dto.CommentCreateRequest;
import com.school.forum.forum.service.CommentService;
import com.school.forum.forum.vo.CommentVO;
import com.school.forum.infrastructure.web.auth.CurrentUserId;
import com.school.forum.infrastructure.web.auth.RequireLogin;
import com.school.forum.infrastructure.web.auth.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口。
 *
 * <p><b>路径不统一挂在 {@code /comments} 下：</b>「某帖的评论列表」
 * 天然属于帖子这个资源（{@code /posts/{id}/comments}），而发表与删除是评论自己的操作。
 * 这与主流 REST 实践一致，也让前端一眼看出列表接口的归属。
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /** 发表评论（{@code parentId} 为 0）或回复（{@code parentId} 非 0） */
    @PostMapping("/comments")
    @RequireLogin
    public Result<CommentVO> create(@CurrentUserId Long userId,
                                    @RequestBody @Valid CommentCreateRequest request) {
        return Result.ok(commentService.create(userId, request));
    }

    /** 帖子的一级评论分页，每条附带前几条回复预览。未登录可用 */
    @GetMapping("/posts/{postId}/comments")
    public Result<PageResult<CommentVO>> list(@PathVariable Long postId,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false, defaultValue = "20") int size,
                                              @CurrentUserId(required = false) Long currentUserId) {
        return Result.ok(commentService.list(postId, cursor, size, currentUserId));
    }

    /**
     * 删除评论。角色从登录态里取，而不是让前端传——
     * 前端传什么都会被伪造，权限判断的依据只能是服务端自己认出来的身份。
     */
    @DeleteMapping("/comments/{commentId}")
    @RequireLogin
    public Result<Void> delete(@PathVariable Long commentId, @CurrentUserId Long userId) {
        commentService.delete(commentId, userId, UserContext.get().role());
        return Result.ok();
    }
}
