package com.aiopenplatform.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SystemConstants {

    /** 图片上传根目录：由 application.yaml 的 app.upload-dir 注入（相对项目根目录） */
    private static String imageUploadDir;

    public static String getImageUploadDir() {
        return imageUploadDir;
    }

    @Value("${app.upload-dir}")
    public void setImageUploadDir(String imageUploadDir) {
        SystemConstants.imageUploadDir = imageUploadDir;
    }

    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
