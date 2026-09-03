package com.aiopenplatform.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @PostMapping("image")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(new File(SystemConstants.getImageUploadDir(), fileName));
            // 返回结果（加上 /imgs 前缀，与数据库存储格式一致）
            log.debug("文件上传成功，{}", fileName);
            return Result.ok("/imgs" + fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/image/delete")
    public Result deleteImage(@RequestParam("name") String filename) {
        String relativePath = filename.replaceFirst("^/imgs", "");
        File file = new File(SystemConstants.getImageUploadDir(), relativePath);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 按哈希分散目录，避免单目录文件过多
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.getImageUploadDir(), StrUtil.format("/uploads/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/uploads/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
