package com.aiopenplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.dto.LoginFormDTO;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.dto.UserDTO;
import com.aiopenplatform.entity.User;
import com.aiopenplatform.mapper.UserMapper;
import com.aiopenplatform.service.IUserService;
import com.aiopenplatform.utils.ClientIpUtils;
import com.aiopenplatform.utils.RegexUtils;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.*;
import static com.aiopenplatform.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 用户服务实现：手机验证码登录、按手机号创建用户与登录态管理。
 * 登录 token 存于 Redis（login:token:*），仅用于控制台接口；/v1 网关使用独立的 API Key 鉴权。
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号,不符合返回错误信息
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //防短信轰炸：同一手机号 60s 内仅允许发送一次验证码
        String limitKey = LOGIN_CODE_LIMIT_KEY + phone;
        Long count = stringRedisTemplate.opsForValue().increment(limitKey);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(limitKey, LOGIN_CODE_LIMIT_TTL, TimeUnit.SECONDS);
        }
        if (count != null && count > 1L) {
            return Result.fail("验证码发送过于频繁，请稍后再试");
        }
        //符合生成验证码
        String code = RandomUtil.randomNumbers(6);

        //保持验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码
        log.debug("发送短信验证码成功，验证码：{}",code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session, HttpServletRequest request) {
        if (loginForm == null) {
            return Result.fail("请输入手机号和验证码");
        }
        //校验手机号和验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //黑名单检查：被拉黑的手机号禁止登录
        if (BooleanUtil.isTrue(stringRedisTemplate.hasKey(BLACKLIST_PHONE_KEY + phone))) {
            return Result.fail("账号已被限制登录，请稍后再试");
        }
        //从redis获取验证码
        String CacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(CacheCode == null || !CacheCode.equals(code)){
            //登录失败计数，达到阈值拉黑（手机号 + 当前IP）
            recordLoginFail(phone, ClientIpUtils.getClientIp(request));
            return Result.fail("验证码错误");
        }

        //用手机号查询数据库，判断用户 是否存在
        User user = query().eq("phone", phone).one();
        
        if(user==null){
            user = createUserWithPhone(phone);
        }

        //保存用户信息到redis当中
        //生成token登录令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象转化为hashmap存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 验证码只能使用一次；成功后同时清理本轮失败计数。
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        stringRedisTemplate.delete(LOGIN_FAIL_KEY + "phone:" + phone);
        stringRedisTemplate.delete(LOGIN_FAIL_KEY + "ip:" + ClientIpUtils.getClientIp(request));
        return Result.ok(token);
    }

    /**
     * 记录登录失败：失败次数达到阈值后，将手机号和来源 IP 拉入黑名单（30 分钟）
     */
    private void recordLoginFail(String phone, String ip) {
        String phoneFailKey = LOGIN_FAIL_KEY + "phone:" + phone;
        Long phoneFailCount = stringRedisTemplate.opsForValue().increment(phoneFailKey);
        if (phoneFailCount != null && phoneFailCount == 1L) {
            stringRedisTemplate.expire(phoneFailKey, BLACKLIST_TTL, TimeUnit.MINUTES);
        }
        if (phoneFailCount != null && phoneFailCount >= LOGIN_FAIL_THRESHOLD) {
            // 拉黑手机号，并清除失败计数
            stringRedisTemplate.opsForValue().set(BLACKLIST_PHONE_KEY + phone, "1", BLACKLIST_TTL, TimeUnit.MINUTES);
            stringRedisTemplate.delete(phoneFailKey);
            log.warn("手机号登录失败次数过多，已拉黑: phone={}", phone);
        }

        // IP 维度同样计数拉黑
        if (ip == null || ip.isEmpty()) {
            return;
        }
        String ipFailKey = LOGIN_FAIL_KEY + "ip:" + ip;
        Long ipFailCount = stringRedisTemplate.opsForValue().increment(ipFailKey);
        if (ipFailCount != null && ipFailCount == 1L) {
            stringRedisTemplate.expire(ipFailKey, BLACKLIST_TTL, TimeUnit.MINUTES);
        }
        if (ipFailCount != null && ipFailCount >= LOGIN_FAIL_THRESHOLD) {
            stringRedisTemplate.opsForValue().set(BLACKLIST_IP_KEY + ip, "1", BLACKLIST_TTL, TimeUnit.MINUTES);
            stringRedisTemplate.delete(ipFailKey);
            log.warn("IP 登录失败次数过多，已拉黑: ip={}", ip);
        }
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(5));
        save(user);
        return user;
    }
}
