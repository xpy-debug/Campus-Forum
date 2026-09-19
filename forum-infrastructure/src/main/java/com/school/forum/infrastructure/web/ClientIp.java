package com.school.forum.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 从请求中还原客户端真实 IP。
 *
 * <p>不能直接用 {@code getRemoteAddr()}：生产环境前面必然有 Nginx 之类的反向代理，
 * 那样取到的是代理的地址，所有用户的 {@code last_login_ip} 会是同一个值——
 * 这个字段也就失去了它唯一的价值（排查异地登录）。
 *
 * <p><b>X-Forwarded-For 是可以伪造的。</b>它由客户端发来的请求头一路追加而成，
 * 最左边那一段就是客户端自己写的。所以这里的取值只用于展示与审计，
 * <b>不能作为限流或风控的依据</b>——那是网关按连接来源统计的职责。
 */
public final class ClientIp {

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final int MAX_LENGTH = 45;

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String ip = firstOf(request.getHeader(HEADER_X_FORWARDED_FOR));
        if (ip == null) {
            ip = request.getHeader(HEADER_X_REAL_IP);
        }
        if (!StringUtils.hasText(ip)) {
            ip = request.getRemoteAddr();
        }
        if (!StringUtils.hasText(ip)) {
            return "";
        }
        // 数据库字段是 VARCHAR(45)（兼容 IPv6），超长会被 MySQL 截断并报错，
        // 在写入前先截断，避免为了一个审计字段影响登录主流程
        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }

    /** 多级代理时取最左边一段（最早的来源），并去掉可能存在的空格 */
    private static String firstOf(String forwardedFor) {
        if (!StringUtils.hasText(forwardedFor)) {
            return null;
        }
        int comma = forwardedFor.indexOf(',');
        String first = comma < 0 ? forwardedFor : forwardedFor.substring(0, comma);
        return first.trim();
    }
}
