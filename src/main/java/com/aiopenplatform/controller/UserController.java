package com.aiopenplatform.controller;


import com.aiopenplatform.dto.LoginFormDTO;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.service.IUserService;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import static com.aiopenplatform.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * <p>
 * 前端控制器
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @PostMapping("/code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        //发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session, HttpServletRequest request){
        //实现登录功能
        return userService.login(loginForm,session,request);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request){
        String token = request.getHeader("Authorization");
        if (token != null && !token.trim().isEmpty()) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token.trim());
        }
        UserHolder.removeUser();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me(){
        //获取当前登录的用户并返回
        log.info("用户点击me");
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }
}
