package com.school.forum.infrastructure.mq.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 开启 MQ 抽象层的配置属性绑定。
 *
 * <p>之所以单独建一个类而不是在 Kafka/Rabbit 的实现类上加
 * {@code @EnableConfigurationProperties}：那两个类都带 {@code @ConditionalOnProperty}，
 * 一旦条件不满足就不会被注册，属性也就没人绑定了。放在这个无条件的配置类里，
 * {@link MqProperties} 在任何 profile 下都能正常注入。
 */
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqAutoConfiguration {
}
