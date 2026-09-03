package com.aiopenplatform.gateway;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Authenticated API Key owner, its application scope and the key row id. */
@Data
@AllArgsConstructor
public class ApiPrincipal {
    private Long userId;
    private Long appId;
    /** tb_api_key.id：Key 级限流/配额以它为单位 */
    private Long keyId;
}
