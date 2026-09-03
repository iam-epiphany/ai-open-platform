package com.aiopenplatform.controller;

import com.aiopenplatform.dto.Result;
import com.aiopenplatform.gateway.PlatformService;
import com.aiopenplatform.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/** Login-protected unified Credits account and ledger endpoints. */
@RestController
@RequestMapping("/credits")
public class CreditAccountController {
    @Resource
    private PlatformService platformService;

    @GetMapping("/account")
    public Result account() {
        return Result.ok(platformService.account(UserHolder.getUser().getId()));
    }

    @GetMapping("/summary")
    public Result summary() {
        return Result.ok(platformService.creditSummary(UserHolder.getUser().getId()));
    }

    @GetMapping("/daily")
    public Result daily(@RequestParam(value = "days", defaultValue = "7") int days) {
        return Result.ok(platformService.creditDaily(UserHolder.getUser().getId(), days));
    }

    @GetMapping("/records")
    public Result records(@RequestParam(value = "type", defaultValue = "") String type,
                          @RequestParam(value = "current", defaultValue = "1") int current,
                          @RequestParam(value = "size", defaultValue = "10") int size) {
        Map<String, Object> result = platformService.creditRecords(UserHolder.getUser().getId(), type, current, size);
        return Result.ok(result);
    }

    @PostMapping("/purchase")
    public Result purchase(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return Result.fail("请选择要购买的 Credits 套餐");
        }
        Object value = body.get("credits");
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0) {
            return Result.fail("请选择要购买的 Credits 套餐");
        }
        try {
            return Result.ok(platformService.purchaseCredits(UserHolder.getUser().getId(), ((Number) value).longValue()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

}
