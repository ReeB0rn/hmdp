package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShopMapper shopMapper;

    /**
     * 根据店铺ID 查询店铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        String keyShop = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(keyShop);
        Shop shop;

        // 缓存击中 返回商铺信息
        if(StrUtil.isNotBlank(shopJson)){
            log.info("商铺缓存击中:{}",id);
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }else if(shopJson != null){
            //缓存击中空 店铺不存在
            log.info("击中空缓存 店铺不存在:{}",id);
            stringRedisTemplate.expire(keyShop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }

        // 缓存未击中 查询数据库
        shop = getById(id);

        // 店铺不存在
        if(shop == null){
            log.info("店铺数据库查询为空:{}",id);
            stringRedisTemplate.opsForValue().set(keyShop,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        shopJson = JSONUtil.toJsonStr(shop);
        log.info("数据库查询到店铺并写入缓存:{}",id);
        stringRedisTemplate.opsForValue().set(keyShop,shopJson,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    /**
     * 更新店铺信息
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            log.info("ShopServiceImpl:update 商铺异常:{}",shop);
            return Result.fail("更新店铺异常");
        }
        // 1. 更新数据库商铺信息
        updateById(shop);
        log.info("ShopServiceImpl:update 更新商铺信息成功{}",shop.getId());
        // 2. 删除Redis缓存信息
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        log.info("ShopServiceImpl:update Redis缓存信息删除成功{}",shop.getId());
        return Result.ok();

    }
}
