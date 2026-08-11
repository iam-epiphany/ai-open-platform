package com.aiopenplatform.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
    /** 手机号：用于管理后台白名单鉴权（admin.phones 配置） */
    private String phone;
}
