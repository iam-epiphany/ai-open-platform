package com.aiopenplatform.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理后台（简单版）：
 * 鉴权采用 application.yaml 的 admin.phones 手机号白名单（不做角色表），
 * 所有额度调整都写账本流水（change_type=1/2），天然具备操作审计。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Value("${admin.phones:}")
    private String adminPhones;

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
        if (StrUtil.isBlank(phone) || StrUtil.isBlank(adminPhones)) {
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
}
