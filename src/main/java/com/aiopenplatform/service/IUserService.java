package com.aiopenplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aiopenplatform.dto.LoginFormDTO;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * <p>
 *  用户服务
 * </p>
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session, HttpServletRequest request);
}
