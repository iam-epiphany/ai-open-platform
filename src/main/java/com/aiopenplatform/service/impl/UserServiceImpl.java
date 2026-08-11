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
import com.aiopenplatform.utils.RegexUtils;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.*;
import static com.aiopenplatform.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
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
        if(CacheCode ==null || !code.equals(CacheCode)){
            //登录失败计数，达到阈值拉黑（手机号 + 当前IP）
            recordLoginFail(phone, getClientIp(request));
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
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        int dayOfMonth = now.getDayOfMonth();

        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result==null||result.size()==0){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num==null || num==0){
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            if ((num&1)==0) {
                break;
            }else{
                count ++;
            }
            num>>>=1;
        }
        return Result.ok(count);
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

    /**
     * 获取客户端真实 IP（兼容反向代理 X-Forwarded-For）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else {
            int idx = ip.indexOf(',');
            if (idx > 0) {
                ip = ip.substring(0, idx);
            }
        }
        return ip;
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(5));
        save(user);
        return user;
    }
}
