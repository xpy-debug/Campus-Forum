package com.school.forum.controller;

import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.mq.core.MqProperties;
import com.school.forum.notification.api.SelfTestApi;
import com.school.forum.notification.api.SelfTestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 骨架自检接口。
 *
 * <p><b>它验证的不只是「服务活着」，而是「四个组件都连得上、消息真的能走通」。</b>
 * 刚搭好骨架时最费时间的往往不是写代码，而是确认环境——
 * 数据库连不上是密码错了还是容器没起？消息发了没收到是主题没建还是消费者没绑定？
 * 这个接口把这些问题一次性回答清楚。
 */
@Slf4j
@Tag(name = "自检", description = "骨架与环境连通性验证")
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final SelfTestApi selfTestApi;
    private final MqProperties mqProperties;
    private final DataSource dataSource;
    private final StringRedisTemplate stringRedisTemplate;

    @Operation(summary = "基础健康检查", description = "逐项检查 MySQL、Redis 的连通性，并回报当前生效的 MQ 实现")
    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("application", "school-forum");
        status.put("time", LocalDateTime.now().toString());
        status.put("mqProvider", mqProperties.getProvider());
        status.put("mysql", checkMysql());
        status.put("redis", checkRedis());
        // MQ 的连通性不在这里同步探测：探测意味着要建连接甚至发消息，
        // 而健康检查会被监控高频调用，把 MQ 探测放进来等于给 MQ 持续加压。
        // 需要用 /health/mq 主动触发一次真实的消息往返。
        status.put("mqHint", "MQ 连通性请调用 POST /health/mq 做一次真实的收发验证");
        return Result.ok(status);
    }

    @Operation(summary = "MQ 链路自检",
            description = "发一条消息并等待消费，验证「生产者 → Broker → 消费者」整条链路是否打通")
    @PostMapping("/mq")
    public Result<SelfTestResult> mqSelfTest(@RequestParam(defaultValue = "manual") String note) {
        return Result.ok(selfTestApi.publishAndAwait(note));
    }

    @Operation(summary = "查看最近一次 MQ 自检结果")
    @GetMapping("/mq")
    public Result<SelfTestResult> lastMqResult() {
        return Result.ok(selfTestApi.lastResult());
    }

    private Map<String, Object> checkMysql() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            result.put("status", valid ? "UP" : "DOWN");
            result.put("database", connection.getCatalog());
            result.put("costMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("status", "DOWN");
            // 把原始异常消息带上是刻意的：这是自检接口，使用者就是开发者本人，
            // 需要看到「Access denied for user」还是「Connection refused」才能定位。
            // 生产环境应通过配置关闭（或限制该接口的访问来源）。
            result.put("error", e.getMessage());
            result.put("costMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    private Map<String, Object> checkRedis() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            stringRedisTemplate.opsForValue().set("forum:health:ping", "1", java.time.Duration.ofMinutes(1));
            String value = stringRedisTemplate.opsForValue().get("forum:health:ping");
            result.put("status", "1".equals(value) ? "UP" : "DOWN");
            result.put("costMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("error", e.getMessage());
            result.put("costMs", System.currentTimeMillis() - start);
        }
        return result;
    }
}
