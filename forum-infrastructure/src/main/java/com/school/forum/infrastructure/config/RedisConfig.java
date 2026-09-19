package com.school.forum.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * <p>Spring Boot 已经自动配置好了 {@code StringRedisTemplate}，
 * 本项目绝大多数场景（计数器、SETNX 幂等、库存、点赞集合）用字符串就够了，
 * 因此不需要额外配置。这里只补一个值类型为对象的 {@code RedisTemplate}，
 * 用于缓存用户信息、帖子详情这类结构化数据。
 *
 * <p><b>序列化方式必须显式指定，不能沿用默认值。</b>
 * {@code RedisTemplate} 的默认序列化器是 JDK 序列化，它有三个致命问题：
 * <ul>
 *   <li>存进去的内容是二进制乱码，用 redis-cli 完全看不懂，排查问题极其痛苦</li>
 *   <li>Java 版本或类结构一变就反序列化失败，且失败后只能删 key 重建</li>
 *   <li>存在反序列化漏洞风险（gadget chain）</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * 构造供 Redis 值序列化使用的 ObjectMapper。
     *
     * <p><b>注意这里没有直接复用 Spring MVC 的那个 ObjectMapper。</b>
     * 缓存里的类型信息、时间格式等需求与 HTTP 接口并不一样，
     * 共用会导致「为了改接口输出格式而意外改变了缓存格式」这类连锁问题。
     *
     * <p><b>⚠ 这个方法刻意不声明为 {@code @Bean}，这是一个踩过的坑。</b>
     * Spring Boot 的 {@code JacksonAutoConfiguration} 用的是
     * {@code @ConditionalOnMissingBean(ObjectMapper.class)}——按<b>类型</b>判断，
     * 不看 Bean 名字。只要容器里出现任何一个 {@code ObjectMapper} Bean，
     * 自动配置就整体退让，于是这个开着 default typing 的 mapper 会被三处同时拿走：
     *
     * <ol>
     *   <li><b>Web 层</b>：所有 HTTP 响应都带上 {@code "@class": "java.util.LinkedHashMap"}，
     *       连 {@code Long} 都被写成 {@code ["java.lang.Long", 3]}，接口契约直接废掉；</li>
     *   <li><b>MQ 消息体</b>：业务事件被塞进类型包装，生产者与消费者的可见格式不再一致；</li>
     *   <li><b>业务代码里的 {@code @RequiredArgsConstructor} 注入点</b>：
     *       凡是构造器注入 {@code ObjectMapper} 的类都会拿到它。
     *       消费端 {@code JsonNode.path("receivedAtMillis").asLong()} 拿到的是
     *       {@code ArrayNode}，而 {@code asLong()} 对非数字节点<b>静默返回 0</b>——
     *       表现为自检的端到端延迟永远是 0、时间显示为 null，且完全没有报错。</li>
     * </ol>
     *
     * <p>第 3 条尤其值得记住：default typing 的破坏力不在于「格式变丑」，
     * 而在于它把数字变成了数组，让下游的类型假设<b>静默失效</b>。
     * 改成普通私有方法之后，容器里只留 Spring Boot 自动配置的那个 ObjectMapper，
     * Web 与业务代码都用它，Redis 用这个专用的，两边互不干扰。
     */
    private ObjectMapper createRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 允许访问私有字段，这样没有 getter 的类也能正常序列化
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 写入类型信息，否则反序列化只能得到 LinkedHashMap 而不是原始类型。
        // 用 LaissezFaireSubTypeValidator 而非默认校验器：默认校验器会因为
        // 类名不在白名单里而拒绝反序列化，本项目缓存的对象类型都是自己可控的。
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        mapper.registerModule(new JavaTimeModule());
        // 缓存里的字段比当前类多时（比如刚回滚了版本）不应该直接报错，
        // 忽略未知字段能让发布过程更平滑
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // key 用 String 序列化：这是最关键的一条。
        // 用 JDK 序列化的话，redis-cli 里看到的是 "\xac\xed\x00\x05t\x00\x0cforum:user:1"，
        // 既没法直观确认 key 是否正确，也没法用 SCAN 按前缀清理。
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<Object> valueSerializer =
                new Jackson2JsonRedisSerializer<>(createRedisObjectMapper(), Object.class);

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
