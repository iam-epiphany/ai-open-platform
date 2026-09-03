package com.aiopenplatform.controller;

import com.aiopenplatform.dto.Result;
import com.aiopenplatform.gateway.PlatformService;
import com.aiopenplatform.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/** Login-protected Credits account and simulated recharge endpoints. */
@RestController
@RequestMapping("/credits")
public class CreditAccountController {
    @Resource
    private PlatformService platformService;

    @GetMapping("/account")
    public Result account() {
        return Result.ok(platformService.account(UserHolder.getUser().getId()));
    }

    @PostMapping("/recharge")
    public Result recharge(@RequestBody Map<String, Long> body) {
        try {
            return Result.ok(platformService.recharge(UserHolder.getUser().getId(), body.get("credits")));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }
}
