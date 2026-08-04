package com.hmdp.cache;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.command.BinaryCommandFactory;
import net.rubyeye.xmemcached.transcoders.StringTranscoder;
import net.rubyeye.xmemcached.utils.AddrUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Memcached 客户端配置（多级缓存 L2）
 * <p>
 * 采用 binary 协议 + 字符串序列化，与 JVM 缓存（L1）、Redis（L3）统一存 JSON 字符串；
 * 连接池默认 5 个连接，单次操作超时 2s。
 * </p>
 */
@Configuration
public class MemcachedConfig {

    @Value("${memcached.servers:127.0.0.1:11211}")
    private String servers;

    @Value("${memcached.pool-size:5}")
    private int poolSize;

    @Value("${memcached.op-timeout:2000}")
    private long opTimeout;

    @Bean(destroyMethod = "shutdown")
    public MemcachedClient memcachedClient() throws Exception {
        XMemcachedClientBuilder builder = new XMemcachedClientBuilder(AddrUtil.getAddresses(servers));
        builder.setConnectionPoolSize(poolSize);
        builder.setOpTimeout(opTimeout);
        builder.setCommandFactory(new BinaryCommandFactory());
        builder.setTranscoder(new StringTranscoder());
        return builder.build();
    }
}
