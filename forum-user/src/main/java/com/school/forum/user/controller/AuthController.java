package com.school.forum.user.controller;

import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.web.ClientIp;
import com.school.forum.infrastructure.web.auth.CurrentUserId;
import com.school.forum.infrastructure.web.auth.RequireLogin;
import com.school.forum.infrastructure.web.auth.UserContext;
import com.school.forum.user.dto.LoginRequest;
import com.school.forum.user.dto.RefreshRequest;
import com.school.forum.user.dto.RegisterRequest;
import com.school.forum.user.service.AuthService;
import com.school.forum.user.vo.LoginVO;
import com.school.forum.user.vo.TokenVO;
import com.school.forum.user.vo.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。上下文路径为 {@code /api}，故实际路径是 {@code /api/auth/*}。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册。
     *
     * <p>注册成功后不直接返回令牌，需要用户再登录一次。多一步操作，换来的是
     * 「注册接口不需要处理登录态」——令牌只在登录一处签发，实现和排查都少一条路径。
     */
    @PostMapping("/register")
    public Result<Long> register(@RequestBody @Valid RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginRequest request,
                                 HttpServletRequest servletRequest) {
        return Result.ok(authService.login(request, ClientIp.of(servletRequest)));
    }

    /**
     * 登出。
     *
     * <p>会话标识直接取自当前请求的登录态，不接受前端传入——
     * 否则任何人都能拿着别人的会话标识把别人踢下线。
     */
    @PostMapping("/logout")
    @RequireLogin
    public Result<Void> logout() {
        authService.logout(UserContext.get().sessionId());
        return Result.ok();
    }

    /**
     * 刷新访问令牌。不需要登录态：调用它的场景恰恰是访问令牌已经过期。
     * 身份由刷新令牌本身证明。
     */
    @PostMapping("/refresh")
    public Result<TokenVO> refresh(@RequestBody @Valid RefreshRequest request) {
        return Result.ok(authService.refresh(request.getRefreshToken()));
    }

    @GetMapping("/me")
    @RequireLogin
    public Result<UserInfoVO> me(@CurrentUserId Long userId) {
        return Result.ok(authService.profile(userId));
    }
}
