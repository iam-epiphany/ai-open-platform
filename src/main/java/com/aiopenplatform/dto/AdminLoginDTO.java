package com.aiopenplatform.dto;

import lombok.Data;

@Data
public class AdminLoginDTO {
    /** 管理员账号（application.yaml 中 admin.username，默认 admin） */
    private String username;
    /** 管理员密码（application.yaml 中 admin.password） */
    private String password;
}
