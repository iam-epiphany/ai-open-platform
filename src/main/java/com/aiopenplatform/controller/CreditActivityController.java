package com.aiopenplatform.controller;

import com.aiopenplatform.dto.Result;
import com.aiopenplatform.service.CreditActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/** Public activity catalogue and authenticated Credits claim endpoint. */
@RestController
@RequestMapping("/credit-activities")
public class CreditActivityController {
    @Resource private CreditActivityService creditActivityService;
    @GetMapping public Result list() { return Result.ok(creditActivityService.list()); }
    @PostMapping("/packages/{packageId}/claim") public Result claim(@PathVariable Long packageId) { return creditActivityService.claim(packageId); }
}
