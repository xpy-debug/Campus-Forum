package com.school.forum.user.config;

import com.school.forum.user.auth.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户域的装配。目前只有两件事：绑定 JWT 配置、提供密码编码器。
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class UserConfig {

    /**
     * 密码编码器。
     *
     * <p>强度保持默认的 10——BCrypt 的强度是指数级的对数（2^strength 轮），
     * 调到 12 会让每次登录多花约 200ms，而抵御离线爆破的收益要靠密钥本身，
     * 不是靠把登录做得慢。真正防在线爆破的是登录失败锁定。
     *
     * <p>只在 forum-user 里定义，但因为整个应用共用一个 Spring 容器，
     * 其他模块也能注入——密码学相关的能力本就该只有一份实现。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
