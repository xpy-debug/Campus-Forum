package com.school.forum.forum.controller;

import com.school.forum.common.result.PageResult;
import com.school.forum.common.result.Result;
import com.school.forum.forum.dto.PostCreateRequest;
import com.school.forum.forum.service.PostService;
import com.school.forum.forum.vo.PostDetailVO;
import com.school.forum.forum.vo.PostVO;
import com.school.forum.infrastructure.web.auth.CurrentUserId;
import com.school.forum.infrastructure.web.auth.RequireLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子接口。上下文路径为 {@code /api}，故实际路径是 {@code /api/posts/*}。
 */
@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /**
     * 发帖。
     *
     * <p>{@code Idempotency-Key} 是可选请求头，前端在按下提交时生成一个 UUID。
     * 网络超时后用户重试会带上同一个 key，服务端返回首次创建的帖子 ID 而不是再发一篇。
     * 声明为可选是有意的：不带这个头也能发帖，只是失去防重能力，
     * 强制要求反而会让 curl 测一下接口都变得别扭。
     *
     * @return 帖子 ID。前端据此跳转到详情页
     */
    @PostMapping
    @RequireLogin
    public Result<Long> create(@CurrentUserId Long userId,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                               @RequestBody @Valid PostCreateRequest request) {
        return Result.ok(postService.create(userId, idempotencyKey, request));
    }

    /**
     * 帖子列表。
     *
     * <p>未登录可用，此时每条的 {@code isLiked} 恒为 false，所以这里的
     * {@code @CurrentUserId} 必须显式写 {@code required = false}。
     *
     * @param boardId 不传表示全站信息流（首页）
     * @param sort    latest | hot，非法值退回 latest
     * @param cursor  上一页返回的 nextCursor，首页不传
     */
    @GetMapping
    public Result<PageResult<PostVO>> list(@RequestParam(required = false) Long boardId,
                                           @RequestParam(required = false, defaultValue = "latest") String sort,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false, defaultValue = "20") int size,
                                           @CurrentUserId(required = false) Long currentUserId) {
        return Result.ok(postService.list(boardId, sort, cursor, size, currentUserId));
    }

    @GetMapping("/{postId}")
    public Result<PostDetailVO> detail(@PathVariable Long postId,
                                       @CurrentUserId(required = false) Long currentUserId) {
        return Result.ok(postService.detail(postId, currentUserId));
    }
}
