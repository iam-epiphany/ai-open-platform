package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 5L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 120L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long CACHE_SHOP_TYPE_TTL = 120L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType";
    /** 热点笔记缓存 key + TTL */
    public static final String CACHE_BLOG_KEY = "cache:blog:";
    public static final Long CACHE_BLOG_TTL = 30L;


    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_COUNT_KEY = "seckill:count:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    //============ 防刷频控 & 黑名单 ============
    /** 接口频控 key：rate:limit:{ip}:{uri} */
    public static final String RATE_LIMIT_KEY = "rate:limit:";
    /** 验证码发送频控 key：login:code:limit:{phone} */
    public static final String LOGIN_CODE_LIMIT_KEY = "login:code:limit:";
    /** 登录失败计数 key：login:fail:phone:{phone} / login:fail:ip:{ip} */
    public static final String LOGIN_FAIL_KEY = "login:fail:";
    /** 黑名单 key：blacklist:phone:{phone} / blacklist:ip:{ip} */
    public static final String BLACKLIST_PHONE_KEY = "blacklist:phone:";
    public static final String BLACKLIST_IP_KEY = "blacklist:ip:";
    /** 频控窗口：每接口每 IP 每 60s 最多请求次数 */
    public static final int RATE_LIMIT_COUNT = 30;
    /** 验证码发送间隔：60s 内同一手机号仅可发送一次 */
    public static final Long LOGIN_CODE_LIMIT_TTL = 60L;
    /** 登录失败次数阈值，达到后拉黑 */
    public static final int LOGIN_FAIL_THRESHOLD = 5;
    /** 拉黑时长（分钟） */
    public static final Long BLACKLIST_TTL = 30L;
}
