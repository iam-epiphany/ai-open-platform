package com.aiopenplatform.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aiopenplatform.dto.AdminLoginDTO;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.cache.JvmCaches;
import com.aiopenplatform.cache.MultiLevelCacheService;
import com.aiopenplatform.entity.TokenActivity;
import com.aiopenplatform.entity.TokenCallLog;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.gateway.PlatformService;
import com.aiopenplatform.mapper.TokenAppMapper;
import com.aiopenplatform.mapper.TokenOrderMapper;
import com.aiopenplatform.mapper.UserMapper;
import com.aiopenplatform.service.ITokenActivityService;
import com.aiopenplatform.service.ITokenCallLogService;
import com.aiopenplatform.service.ITokenSkuService;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.BLACKLIST_IP_KEY;
import static com.aiopenplatform.utils.RedisConstants.BLACKLIST_TTL;
import static com.aiopenplatform.utils.RedisConstants.LOGIN_FAIL_KEY;
import static com.aiopenplatform.utils.RedisConstants.LOGIN_FAIL_THRESHOLD;
import static com.aiopenplatform.utils.RedisConstants.LOGIN_USER_KEY;
import static com.aiopenplatform.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 管理后台（简单版）：
 * 鉴权双通道：① 管理员账号密码登录（admin.username/admin.password，POST /admin/login）；
 * ② application.yaml 的 admin.phones 手机号白名单（历史方式，不做角色表）。
 * 所有额度调整都写账本流水（change_type=1/2），天然具备操作审计。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Value("${admin.phones:}")
    private String adminPhones;

    /** 管理员账号（application.yaml 中 admin.username） */
    @Value("${admin.username:admin}")
    private String adminUsername;

    /** 管理员密码（application.yaml 中 admin.password） */
    @Value("${admin.password:}")
    private String adminPassword;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserMapper userMapper;
    @Resource
    private TokenOrderMapper orderMapper;
    @Resource
    private TokenAppMapper appMapper;
    @Resource
    private ITokenCallLogService callLogService;
    @Resource
    private ITokenSkuService skuService;
    @Resource
    private ITokenActivityService activityService;
    @Resource
    private PlatformService platformService;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private MultiLevelCacheService multiLevelCacheService;

    /**
     * 管理员账号密码登录：校验通过后签发与普通用户同一套 Redis token，
     * 登录态经 RefreshTokenInterceptor 自动填充 UserHolder（phone=admin.username），isAdmin() 据此放行 /admin/**
     */
    @PostMapping("/login")
    public Result login(@RequestBody AdminLoginDTO loginForm, HttpServletRequest request) {
        if (loginForm == null) {
            return Result.fail("账号密码不能为空");
        }
        String username = loginForm.getUsername();
        String password = loginForm.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            return Result.fail("账号密码不能为空");
        }
        // IP 黑名单检查：被拉黑的 IP 禁止登录
        String ip = getClientIp(request);
        if (BooleanUtil.isTrue(stringRedisTemplate.hasKey(BLACKLIST_IP_KEY + ip))) {
            return Result.fail("登录失败次数过多，请 30 分钟后再试");
        }
        // 账号密码校验（演示项目明文比对；生产可改为 PasswordEncoder.matches 校验密文）
        if (!adminUsername.equals(username) || !adminPassword.equals(password)) {
            recordLoginFail(ip);
            return Result.fail("账号或密码错误");
        }
        // 生成 token，构造管理员 UserDTO（id=0 占位，phone 存账号用于 isAdmin 判定）存入 Redis Hash
        String token = UUID.randomUUID().toString(true);
        UserDTO adminDTO = new UserDTO();
        adminDTO.setId(0L);
        adminDTO.setNickName("管理员");
        adminDTO.setPhone(adminUsername);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                adminDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) ->
                                fieldValue == null ? null : fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.delete(LOGIN_FAIL_KEY + "ip:" + ip);
        log.info("管理员登录成功: username={}, ip={}", username, ip);
        return Result.ok(token);
    }

    /**
     * 前端判断当前用户是否为管理员（控制台是否显示管理入口）
     */
    @GetMapping("/check")
    public Result check() {
        Map<String, Object> data = new HashMap<>();
        data.put("admin", isAdmin());
        return Result.ok(data);
    }

    /**
     * 平台总览：用户数 / 订单数 / 应用数 / 发放总量 / 消耗总量 / 调用次数
     */
    @GetMapping("/overview")
    public Result overview() {
        if (!isAdmin()) {
            return Result.fail("无权限");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("userCount", userMapper.selectCount(null));
        data.put("orderCount", orderMapper.selectCount(null));
        data.put("appCount", appMapper.selectCount(null));
        data.put("grantTotal", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(change_amount),0) FROM tb_credit_ledger WHERE change_amount>0", Long.class));
        data.put("consumeTotal", callLogService.sumTotalTokens());
        data.put("callCount", callLogService.countTotal());
        return Result.ok(data);
    }

    /**
     * 全部调用日志（分页）
     */
    @GetMapping("/call-logs")
    public Result callLogs(@RequestParam(value = "current", defaultValue = "1") int current,
                           @RequestParam(value = "size", defaultValue = "10") int size) {
        if (!isAdmin()) {
            return Result.fail("无权限");
        }
        IPage<TokenCallLog> page = callLogService.pageAll(current, size);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    /** Credits 平台统计：今日调用、今日消耗、模型调用排行与余额总量。 */
    @GetMapping("/credit-overview")
    public Result creditOverview() {
        if (!isAdmin()) return Result.fail("无权限");
        Map<String, Object> data = new HashMap<>();
        data.put("todayCallCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_ai_call_log WHERE status=1 AND DATE(create_time)=CURDATE()", Long.class));
        data.put("todayCreditCost", jdbcTemplate.queryForObject("SELECT IFNULL(SUM(credit_cost),0) FROM tb_ai_call_log WHERE status=1 AND DATE(create_time)=CURDATE()", Long.class));
        data.put("creditBalance", jdbcTemplate.queryForObject("SELECT IFNULL(SUM(balance),0) FROM tb_credit_account", Long.class));
        data.put("modelRanking", jdbcTemplate.queryForList("SELECT model,COUNT(*) call_count,SUM(credit_cost) credit_cost FROM tb_ai_call_log WHERE status=1 GROUP BY model ORDER BY call_count DESC"));
        return Result.ok(data);
    }

    /** Credits 调用审计日志分页。 */
    @GetMapping("/ai-call-logs")
    public Result aiCallLogs(@RequestParam(value = "current", defaultValue = "1") int current,
                             @RequestParam(value = "size", defaultValue = "10") int size) {
        if (!isAdmin()) return Result.fail("无权限");
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.min(100, Math.max(1, size));
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_ai_call_log", Long.class);
        return Result.ok(jdbcTemplate.queryForList("SELECT id,request_id AS requestId,user_id AS userId,app_id AS appId,model,prompt_tokens AS promptTokens,completion_tokens AS completionTokens,credit_cost AS creditCost,latency,status,error_message AS errorMessage,create_time AS createTime FROM tb_ai_call_log ORDER BY create_time DESC LIMIT ?,?", (safeCurrent - 1) * safeSize, safeSize), total);
    }

    /**
     * 全部 SKU 列表（含下架，管理后台编辑用）
     */
    @GetMapping({"/credit-packages", "/skus"})
    public Result skus() {
        if (!isAdmin()) {
            return Result.fail("无权限");
        }
        return Result.ok(skuService.list());
    }

    @PostMapping({"/credit-packages", "/skus"})
    public Result createCreditPackage(@RequestBody TokenSku sku) {
        if (!isAdmin()) return Result.fail("无权限");
        String error = validatePackage(sku, false);
        if (error != null) return Result.fail(error);
        skuService.createSku(sku);
        return Result.ok(sku.getId());
    }

    @PutMapping({"/credit-packages", "/skus"})
    public Result updateCreditPackage(@RequestBody TokenSku sku) {
        if (!isAdmin()) return Result.fail("无权限");
        String error = validatePackage(sku, true);
        if (error != null) return Result.fail(error);
        try {
            skuService.updateSku(sku);
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/credit-activities")
    public Result activities() {
        if (!isAdmin()) return Result.fail("无权限");
        return Result.ok(activityService.list());
    }

    @PostMapping("/credit-activities")
    public Result createActivity(@RequestBody TokenActivity activity) {
        if (!isAdmin()) return Result.fail("无权限");
        String error = validateActivity(activity, false);
        if (error != null) return Result.fail(error);
        activityService.save(activity);
        evictActivity(activity.getId());
        return Result.ok(activity.getId());
    }

    @PutMapping("/credit-activities")
    public Result updateActivity(@RequestBody TokenActivity activity) {
        if (!isAdmin()) return Result.fail("无权限");
        String error = validateActivity(activity, true);
        if (error != null) return Result.fail(error);
        if (!activityService.updateById(activity)) return Result.fail("活动不存在");
        evictActivity(activity.getId());
        return Result.ok();
    }

    /**
     * 管理员调整用户额度：type=1 发放；type=2 回收（均写账本流水）
     */
    @PutMapping({"/credits", "/quota"})
    public Result adjustQuota(@RequestBody Map<String, Object> body) {
        if (!isAdmin()) {
            return Result.fail("无权限");
        }
        if (body == null) {
            return Result.fail("参数错误");
        }
        if (!(body.get("userId") instanceof Number) || !(body.get("amount") instanceof Number)
                || !(body.get("type") instanceof Number)) {
            return Result.fail("参数错误");
        }
        long userId = ((Number) body.get("userId")).longValue();
        long amount = ((Number) body.get("amount")).longValue();
        int type = ((Number) body.get("type")).intValue();
        if (userId <= 0 || amount <= 0 || (type != 1 && type != 2)) return Result.fail("参数错误");
        if (userMapper.selectById(userId) == null) return Result.fail("用户不存在");
        String reference = "A" + java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            long balance = platformService.adjustCredits(userId, type == 1 ? amount : -amount, reference,
                    "管理员" + (type == 1 ? "发放" : "扣减"));
            log.info("管理员调整 Credits: operator={}, userId={}, type={}, amount={}", currentPhone(), userId, type, amount);
            return Result.ok(java.util.Collections.singletonMap("balance", balance));
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    private String validatePackage(TokenSku sku, boolean requireId) {
        if (sku == null || (requireId && sku.getId() == null)) return "Credits 包参数不完整";
        if (StrUtil.isBlank(sku.getPackageName())) return "Credits 包名称不能为空";
        if (sku.getTokenAmount() == null || sku.getTokenAmount() <= 0) return "Credits 数量必须大于 0";
        if (sku.getStock() == null || sku.getStock() < 0) return "库存不能小于 0";
        if (sku.getType() == null || (sku.getType() != 1 && sku.getType() != 2)) return "包类型不正确";
        if (sku.getStatus() == null || (sku.getStatus() != 0 && sku.getStatus() != 1)) return "状态不正确";
        if (sku.getType() == 1) sku.setLimitCount(1);
        if (sku.getLimitCount() == null || sku.getLimitCount() <= 0) return "限购数量必须大于 0";
        if (sku.getModelId() == null) sku.setModelId(0L);
        if (sku.getModelName() == null) sku.setModelName("");
        return null;
    }

    private String validateActivity(TokenActivity activity, boolean requireId) {
        if (activity == null || (requireId && activity.getId() == null)) return "活动参数不完整";
        if (StrUtil.isBlank(activity.getTitle())) return "活动标题不能为空";
        if (StrUtil.isBlank(activity.getSkuIds())) return "请填写活动包含的 Credits 包 ID";
        for (String value : activity.getSkuIds().split(",")) {
            try {
                Long skuId = Long.valueOf(value.trim());
                if (skuService.getById(skuId) == null) return "Credits 包 #" + skuId + " 不存在";
            } catch (NumberFormatException e) {
                return "Credits 包 ID 格式不正确";
            }
        }
        if (activity.getStatus() == null || (activity.getStatus() != 0 && activity.getStatus() != 1)) return "活动状态不正确";
        if (activity.getStartTime() == null || activity.getEndTime() == null) return "请选择活动时间";
        if (!activity.getStartTime().isBefore(activity.getEndTime())) return "活动开始时间必须早于结束时间";
        return null;
    }

    private void evictActivity(Long activityId) {
        if (activityId != null) {
            multiLevelCacheService.delete(JvmCaches.CACHE_ACTIVITY,
                    com.aiopenplatform.utils.RedisConstants.TOKEN_ACTIVITY_KEY + activityId);
        }
    }

    private boolean isAdmin() {
        String phone = currentPhone();
        if (StrUtil.isBlank(phone)) {
            return false;
        }
        // 管理员账号登录（admin.username，如 admin）
        if (phone.equals(adminUsername)) {
            return true;
        }
        // 手机号白名单（admin.phones）
        if (StrUtil.isBlank(adminPhones)) {
            return false;
        }
        for (String p : adminPhones.split(",")) {
            if (phone.equals(p.trim())) {
                return true;
            }
        }
        return false;
    }

    private String currentPhone() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getPhone();
    }

    /**
     * 记录管理员登录失败：IP 维度计数，达到阈值拉黑 30 分钟
     */
    private void recordLoginFail(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        String ipFailKey = LOGIN_FAIL_KEY + "ip:" + ip;
        Long ipFailCount = stringRedisTemplate.opsForValue().increment(ipFailKey);
        if (ipFailCount != null && ipFailCount == 1L) {
            stringRedisTemplate.expire(ipFailKey, BLACKLIST_TTL, TimeUnit.MINUTES);
        }
        if (ipFailCount != null && ipFailCount >= LOGIN_FAIL_THRESHOLD) {
            stringRedisTemplate.opsForValue().set(BLACKLIST_IP_KEY + ip, "1", BLACKLIST_TTL, TimeUnit.MINUTES);
            stringRedisTemplate.delete(ipFailKey);
            log.warn("管理员登录失败次数过多，已拉黑 IP: ip={}", ip);
        }
    }

    /**
     * 获取客户端真实 IP（兼容反向代理 X-Forwarded-For）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else {
            int idx = ip.indexOf(',');
            if (idx > 0) {
                ip = ip.substring(0, idx);
            }
        }
        return ip;
    }
}
