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
import com.aiopenplatform.entity.TokenCallLog;
import com.aiopenplatform.entity.TokenLedger;
import com.aiopenplatform.entity.UserQuota;
import com.aiopenplatform.mapper.TokenAppMapper;
import com.aiopenplatform.mapper.TokenLedgerMapper;
import com.aiopenplatform.mapper.TokenOrderMapper;
import com.aiopenplatform.mapper.UserMapper;
import com.aiopenplatform.service.ITokenCallLogService;
import com.aiopenplatform.service.ITokenLedgerService;
import com.aiopenplatform.service.ITokenSkuService;
import com.aiopenplatform.service.IUserQuotaService;
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
    private TokenLedgerMapper ledgerMapper;
    @Resource
    private ITokenCallLogService callLogService;
    @Resource
    private ITokenLedgerService tokenLedgerService;
    @Resource
    private IUserQuotaService userQuotaService;
    @Resource
    private ITokenSkuService skuService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 管理员账号密码登录：校验通过后签发与普通用户同一套 Redis token，
     * 登录态经 RefreshTokenInterceptor 自动填充 UserHolder（phone=admin.username），isAdmin() 据此放行 /admin/**
     */
    @PostMapping("/login")
    public Result login(@RequestBody AdminLoginDTO loginForm, HttpServletRequest request) {
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
        data.put("grantTotal", ledgerMapper.sumByChangeType(1));
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
    @GetMapping("/skus")
    public Result skus() {
        if (!isAdmin()) {
            return Result.fail("无权限");
        }
        return Result.ok(skuService.list());
    }

    /**
     * 管理员调整用户额度：type=1 发放；type=2 回收（均写账本流水）
     */
    @PutMapping("/quota")
    public Result adjustQuota(@RequestBody Map<String, Object> body) {
        if (!isAdmin()) {
            return Result.fail("无权限");
        }
        Long userId = ((Number) body.get("userId")).longValue();
        Long modelId = body.get("modelId") == null ? 0L : ((Number) body.get("modelId")).longValue();
        Long amount = ((Number) body.get("amount")).longValue();
        Integer type = ((Number) body.get("type")).intValue();
        if (userId == null || amount == null || amount <= 0 || (type != 1 && type != 2)) {
            return Result.fail("参数错误");
        }
        if (type == 1) {
            userQuotaService.grantQuota(userId, modelId, amount);
        } else {
            boolean ok = userQuotaService.deductQuota(userId, modelId, amount);
            if (!ok) {
                return Result.fail("该用户余额不足");
            }
        }
        // 写账本（管理员操作 order_id=0，balance_after 回读）
        UserQuota quota = userQuotaService.getQuotaFromDb(userId, modelId);
        TokenLedger ledger = new TokenLedger();
        ledger.setUserId(userId);
        ledger.setOrderId(0L);
        ledger.setChangeType(type);
        ledger.setChangeAmount(amount);
        ledger.setBalanceAfter(quota == null ? 0L : quota.getBalance());
        tokenLedgerService.save(ledger);
        log.info("管理员调整额度: operator={}, userId={}, modelId={}, type={}, amount={}",
                currentPhone(), userId, modelId, type, amount);
        return Result.ok();
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
