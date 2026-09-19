package com.school.forum.forum.controller;

import com.school.forum.common.result.Result;
import com.school.forum.forum.dto.LikeRequest;
import com.school.forum.forum.service.LikeService;
import com.school.forum.forum.vo.LikeVO;
import com.school.forum.infrastructure.web.auth.CurrentUserId;
import com.school.forum.infrastructure.web.auth.RequireLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点赞接口。帖子与评论共用，靠 {@code targetType} 区分。
 *
 * <p><b>两个方法都返回完整的点赞结果而不是空响应：</b>点赞数在服务端是
 * 「Redis 实时真相」，前端拿返回值直接刷新按钮上的数字，就不必再发一次查询。
 */
@RestController
@RequestMapping("/likes")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    /**
     * 点赞。重复点赞不报错，返回当前状态（幂等）。
     *
     * <p>接口立刻返回，此时数据只落在 Redis，数据库由 MQ 消费者异步补齐。
     * 对用户来说数字已经变了，这就是设计要的效果。
     */
    @PostMapping
    @RequireLogin
    public Result<LikeVO> like(@CurrentUserId Long userId,
                               @RequestBody @Valid LikeRequest request) {
        return Result.ok(likeService.like(userId, request.getTargetType(), request.getTargetId()));
    }

    /**
     * 取消点赞。
     *
     * <p><b>参数走 query 而不是请求体：</b>HTTP 规范没有禁止 DELETE 带 body，
     * 但部分网关与代理会把它丢掉，从而变成一个「删掉了什么都不知道」的请求。
     * query 参数没有这个风险。
     */
    @DeleteMapping
    @RequireLogin
    public Result<LikeVO> unlike(@CurrentUserId Long userId,
                                 @RequestParam Integer targetType,
                                 @RequestParam Long targetId) {
        return Result.ok(likeService.unlike(userId, targetType, targetId));
    }
}
