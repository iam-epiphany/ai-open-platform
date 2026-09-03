package com.aiopenplatform.config;

import cn.hutool.core.util.StrUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    /** 与 spring.redis 保持同一数据源，避免改配置后 Redisson 静默连旧地址 */
    @Value("${spring.redis.host:127.0.0.1}")
    private String host;
    @Value("${spring.redis.port:6370}")
    private int port;
    @Value("${spring.redis.password:}")
    private String password;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        if (StrUtil.isNotBlank(password)) {
            config.useSingleServer().setAddress(address).setPassword(password);
        } else {
            config.useSingleServer().setAddress(address);
        }
        return Redisson.create(config);
    }
}
