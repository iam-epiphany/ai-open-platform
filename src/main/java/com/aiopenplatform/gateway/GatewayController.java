package com.aiopenplatform.gateway;

import com.aiopenplatform.gateway.dto.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public, OpenAI-compatible gateway. Authentication is enforced by ApiKeyInterceptor. */
@Slf4j
@RestController
@RequestMapping("/v1")
public class GatewayController {
    @Resource
    private PlatformService platformService;

    @PostMapping("/chat/completions")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        try {
            return ResponseEntity.ok(platformService.chat(ApiPrincipalHolder.get(), request));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request_error", e.getMessage());
        } catch (IllegalStateException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("余额不足") ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.BAD_GATEWAY;
            return error(status, "api_error", e.getMessage());
        } catch (RuntimeException e) {
            // 兜底：保证 /v1 始终返回 OpenAI 兼容的 error 结构
            log.error("网关调用异常", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "api_error", "服务器内部错误");
        }
    }

    @GetMapping("/models")
    public ResponseEntity<?> models() {
        ApiPrincipal principal = ApiPrincipalHolder.get();
        java.util.List<Map<String, Object>> models = platformService.models(principal == null ? null : principal.getAppId());
        java.util.List<Map<String, Object>> data = new java.util.ArrayList<>();
        for (Map<String, Object> model : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model.get("code"));
            item.put("object", "model");
            item.put("owned_by", model.get("provider"));
            item.put("display_name", model.get("display_name"));
            data.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String type, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message); detail.put("type", type);
        return ResponseEntity.status(status).body(java.util.Collections.singletonMap("error", detail));
    }
}
