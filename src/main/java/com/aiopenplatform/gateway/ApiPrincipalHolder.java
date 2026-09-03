package com.aiopenplatform.gateway;

/** Request-scoped API principal; cleared by the authentication interceptor. */
public final class ApiPrincipalHolder {
    private static final ThreadLocal<ApiPrincipal> PRINCIPAL = new ThreadLocal<>();

    private ApiPrincipalHolder() { }

    public static void set(ApiPrincipal principal) { PRINCIPAL.set(principal); }
    public static ApiPrincipal get() { return PRINCIPAL.get(); }
    public static void clear() { PRINCIPAL.remove(); }
}
