package com.aiopenplatform;

import com.aiopenplatform.controller.CreditAccountController;
import com.aiopenplatform.controller.UserController;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.utils.RefreshTokenInterceptor;
import com.aiopenplatform.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.aiopenplatform.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiOpenPlatformApplicationTests {

    @AfterEach
    void cleanThreadLocal() {
        UserHolder.removeUser();
    }

    @Test
    void refreshInterceptorClearsIdentityAtBothRequestBoundaries() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(redis);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Authorization")).thenReturn(null);

        UserHolder.saveUser(new UserDTO());
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserHolder.getUser());

        UserHolder.saveUser(new UserDTO());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(UserHolder.getUser());
    }

    @Test
    void logoutDeletesServerSessionAndClearsCurrentIdentity() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("test-token");
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "stringRedisTemplate", redis);
        UserHolder.saveUser(new UserDTO());

        Result result = controller.logout(request);

        assertTrue(result.getSuccess());
        assertNull(UserHolder.getUser());
        verify(redis).delete(LOGIN_USER_KEY + "test-token");
    }

    @Test
    void purchaseRejectsMissingBodyWithoutServerError() {
        CreditAccountController controller = new CreditAccountController();
        Result result = controller.purchase(null);

        assertFalse(result.getSuccess());
    }

}
