package com.aiopenplatform.gateway;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Authenticated API Key owner and its application scope. */
@Data
@AllArgsConstructor
public class ApiPrincipal {
    private Long userId;
    private Long appId;
}
