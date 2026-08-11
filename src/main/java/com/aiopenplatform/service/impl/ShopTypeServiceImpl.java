package com.aiopenplatform.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.entity.ShopType;
import com.aiopenplatform.mapper.ShopTypeMapper;
import com.aiopenplatform.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        List<ShopType> shopTypeList = null;
        if(StrUtil.isNotBlank(json)){
            log.info("cache命中");
            shopTypeList = JSONUtil.parseArray(json).toList(ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 4.不存在，查询商铺类型列表
        shopTypeList = query()
                .orderByAsc("sort")
                .list();
        // 5.不存在，返回错误
        if (CollUtil.isEmpty(shopTypeList)){
            return Result.fail("商铺类型列表不存在");
        }
        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypeList));
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY , CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 7.返回
        return Result.ok(shopTypeList);
    }
}
