package com.aiopenplatform.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * API Key 级限流（租户配额层，区别于 IP 防刷层）：
 * <ul>
 *     <li>RPM（每分钟请求数）：Redis 令牌桶，容量=每分钟配额，按配额/60 每秒匀速补充，允许突发且平滑；</li>
 *     <li>TPM（每分钟 token 数）：60s 滑动窗口 ZSET 精确累计，消耗量取请求的预占估算
 *         （与 Credits 预占同一估算口径，入口即控成本）；</li>
 *     <li>维度为 tb_api_key.id：与鉴权、计费同一身份，天然支持多 Key 隔离；超限抛
 *         {@link ApiRateLimitException}，由网关映射为 HTTP 429 rate_limit_error。</li>
 * </ul>
 */
@Slf4j
@Service
public class KeyRateLimitService {

    private static final String RPM_KEY = "rate:key:rpm:";
    private static final String TPM_KEY = "rate:key:tpm:";

    private final DefaultRedisScript<Long> rpmScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> tpmScript = new DefaultRedisScript<>();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${ai.limits.rpm-per-key:120}")
    private long rpmPerKey;
    @Value("${ai.limits.tpm-per-key:200000}")
    private long tpmPerKey;

    public KeyRateLimitService() {
        rpmScript.setLocation(new ClassPathResource("lua/rate_rpm.lua"));
        rpmScript.setResultType(Long.class);
        tpmScript.setLocation(new ClassPathResource("lua/rate_tpm.lua"));
        tpmScript.setResultType(Long.class);
    }

    /** RPM 令牌桶取 1 个令牌；超限返回 false（由调用方决定 429）。 */
    public boolean tryAcquireRpm(Long keyId) {
        if (keyId == null) {
            return true;
        }
        long now = System.currentTimeMillis() / 1000;
        double perSecond = rpmPerKey / 60.0;
        Long ok = stringRedisTemplate.execute(rpmScript,
                Arrays.asList(RPM_KEY + keyId),
                String.valueOf(rpmPerKey), String.valueOf(perSecond), String.valueOf(now));
        boolean acquired = ok != null && ok == 1L;
        if (!acquired) {
            log.warn("API Key 触发 RPM 限流: keyId={}, limit={}/min", keyId, rpmPerKey);
        }
        return acquired;
    }

    /** TPM 滑动窗口记账：本次消耗 estimatedTokens，超限返回 false。 */
    public boolean tryAcquireTpm(Long keyId, long estimatedTokens) {
        if (keyId == null || estimatedTokens <= 0) {
            return true;
        }
        long now = System.currentTimeMillis() / 1000;
        String member = estimatedTokens + ":" + ThreadLocalRandom.current().nextLong();
        Long ok = stringRedisTemplate.execute(tpmScript,
                Arrays.asList(TPM_KEY + keyId),
                "60", String.valueOf(tpmPerKey), String.valueOf(now),
                String.valueOf(estimatedTokens), member);
        boolean acquired = ok != null && ok == 1L;
        if (!acquired) {
            log.warn("API Key 触发 TPM 限流: keyId={}, limit={}/min, reqTokens={}", keyId, tpmPerKey, estimatedTokens);
        }
        return acquired;
    }
}
