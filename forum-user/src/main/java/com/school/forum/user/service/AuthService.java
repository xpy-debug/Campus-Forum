package com.school.forum.user.service;

import com.school.forum.user.dto.LoginRequest;
import com.school.forum.user.dto.RegisterRequest;
import com.school.forum.user.vo.LoginVO;
import com.school.forum.user.vo.TokenVO;
import com.school.forum.user.vo.UserInfoVO;

/**
 * 注册、登录与登录态维护。
 */
public interface AuthService {

    /**
     * 注册。
     *
     * @return 新用户的 ID
     */
    Long register(RegisterRequest request);

    /**
     * 登录。
     *
     * @param clientIp 客户端 IP，仅用于记录 {@code last_login_ip}，可为空
     */
    LoginVO login(LoginRequest request, String clientIp);

    /**
     * 用刷新令牌换取新的访问令牌。
     *
     * <p>刷新令牌本身不变——换发新刷新令牌（轮转）能进一步降低泄露风险，
     * 但需要处理「旧令牌被并发使用」的判定，当前场景不值得这个复杂度。
     */
    TokenVO refresh(String refreshToken);

    /** 登出。按会话标识注销令牌，只影响当前设备 */
    void logout(String sessionId);

    /** 查询本人资料 */
    UserInfoVO profile(Long userId);
}
