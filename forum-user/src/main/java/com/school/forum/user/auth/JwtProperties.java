package com.school.forum.user.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置，对应 {@code application.yml} 中的 {@code forum.jwt.*}。
 *
 * <p><b>secret 不写默认值。</b>配置项给了默认值，就意味着生产环境忘记配置时
 * 会静默使用一个人人可见的密钥——攻击者可以据此伪造任意用户的令牌。
 * 宁可启动失败，也不要带着公开密钥上线（校验在 {@link JwtTokenProvider} 中完成）。
 */
@Data
@ConfigurationProperties(prefix = "forum.jwt")
public class JwtProperties {

    /** 签名密钥，HS256 要求不短于 32 字节 */
    private String secret;

    /** AccessToken 有效期（分钟）。短有效期换取「泄露后的可用窗口有限」 */
    private int accessTtlMinutes = 30;

    /** RefreshToken 有效期（天）。它只在刷新接口上使用，暴露面小，可以放长 */
    private int refreshTtlDays = 7;

    /** 签发者标识，会写入 iss 声明并在校验时比对，防止不同系统签发的令牌互相通用 */
    private String issuer = "school-forum";
}
