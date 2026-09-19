package com.school.forum.points.controller;

import com.school.forum.common.result.PageResult;
import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.web.auth.CurrentUserId;
import com.school.forum.infrastructure.web.auth.RequireLogin;
import com.school.forum.points.service.PointsAccountService;
import com.school.forum.points.service.SigninService;
import com.school.forum.points.vo.PointsAccountVO;
import com.school.forum.points.vo.PointsRecordVO;
import com.school.forum.points.vo.SigninResultVO;
import com.school.forum.points.vo.SigninStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分与签到接口。上下文路径为 {@code /api}，故实际路径是 {@code /api/points/*}。
 *
 * <p><b>整个类都要求登录</b>：积分是「我的」数据，没有一个接口在未登录时有意义。
 * 因此注解打在类上，而不是每个方法上——少写四个注解的同时，
 * 也杜绝了「新加一个方法忘记加注解」这个最容易出的错。
 */
@RestController
@RequestMapping("/points")
@RequireLogin
@RequiredArgsConstructor
public class PointsController {

    private final SigninService signinService;
    private final PointsAccountService accountService;

    /**
     * 签到页状态：本月日历、连续天数、全勤进度。
     *
     * <p>一次给全，前端渲染月历不需要再发请求。
     */
    @GetMapping("/signin/status")
    public Result<SigninStatusVO> signinStatus(@CurrentUserId Long userId) {
        return Result.ok(signinService.status(userId));
    }

    /**
     * 签到。
     *
     * <p>返回 200 而不是错误码，即使今天已经签过——重复签到是正常状态，
     * 由 {@code data.success} 表达结果。理由见 {@code SigninResultVO}。
     */
    @PostMapping("/signin")
    public Result<SigninResultVO> signin(@CurrentUserId Long userId) {
        return Result.ok(signinService.signin(userId));
    }

    /** 我的积分账户 */
    @GetMapping("/account")
    public Result<PointsAccountVO> account(@CurrentUserId Long userId) {
        return Result.ok(accountService.account(userId));
    }

    /**
     * 我的积分明细。
     *
     * @param cursor 上一页返回的 nextCursor，首页不传
     * @param size   每页条数，服务端收敛到 1~100（见 {@code PageSizes}）
     */
    @GetMapping("/records")
    public Result<PageResult<PointsRecordVO>> records(@CurrentUserId Long userId,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false, defaultValue = "20") int size) {
        return Result.ok(accountService.records(userId, cursor, size));
    }
}
