package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String typeListJson = stringRedisTemplate.opsForValue().get("typeList");
        List<ShopType> typeList;
        // 缓存击中 返回
        if(StrUtil.isNotBlank(typeListJson)){
            log.info("ShopType 缓存击中:{}",typeListJson);
            typeList = JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 缓存未击中
        typeList = query().orderByAsc("sort").list();
        typeListJson = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set("typeList", typeListJson);
        log.info("ShopType 缓存未击中:{}", typeListJson);
        return Result.ok(typeList);
    }
}
