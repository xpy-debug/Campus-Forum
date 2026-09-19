package com.school.forum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

/**
 * 应用入口。
 *
 * <p>本项目是<b>单体多模块</b>架构：所有业务模块最终都打包进这一个进程，
 * 但模块之间用 Maven 依赖和 ArchUnit 规则强制隔离边界。
 * 这样做的取舍是：
 * <ul>
 *   <li>拿到了单体部署简单、调用无网络开销、事务能跨模块的好处</li>
 *   <li>同时保留了「随时能按模块拆出去」的可能性——前提是边界没被破坏</li>
 * </ul>
 *
 * <p>真正需要微服务的场景（独立扩缩容、独立发布、故障隔离）在本项目的
 * 规模下都不成立，过早拆分换来的是分布式事务和运维复杂度。
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.school.forum")
public class ForumApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(ForumApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String[] profiles = env.getActiveProfiles();
        String mqProvider = env.getProperty("forum.mq.provider", "未配置");

        log.info("""

                        ┌─────────────────────────────────────────────────────────┐
                        │  校园论坛服务启动完成                                    │
                        ├─────────────────────────────────────────────────────────┤
                        │  接口地址 : http://localhost:{}{}
                        │  接口文档 : http://localhost:{}{}/swagger-ui.html
                        │  健康检查 : http://localhost:{}{}/actuator/health
                        │  运行环境 : {}
                        │  消息中间件: {}
                        └─────────────────────────────────────────────────────────┘
                        """,
                port, contextPath,
                port, contextPath,
                port, contextPath,
                profiles.length == 0 ? "default" : String.join(",", profiles),
                mqProvider);
    }
}
