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
        Shop shop = queryWithPassThrough(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 缓存击穿 + 缓存穿透 查询商铺信息方法
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id)  {
        String keyShop = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(keyShop);
        Shop shop;
        try{
            // 缓存击中 返回商铺信息
            if(StrUtil.isNotBlank(shopJson)){
                log.info("商铺缓存击中:{}",id);
                return JSONUtil.toBean(shopJson, Shop.class);
            }else if(shopJson != null){
                //缓存击中空 店铺不存在
                log.info("击中空缓存 店铺不存在:{}",id);
                return null;
            }

            // 获取互斥锁
            if(!tryLock(LOCK_SHOP_KEY+id)){
                log.info("获取互斥锁失败 重试");
                Thread.sleep(100);
                return queryWithPassThrough(id);
            }
            // 双重检查
            shopJson = stringRedisTemplate.opsForValue().get(keyShop);
            if(StrUtil.isNotBlank(shopJson)){
                log.info("双重检查检查到缓存");
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 缓存未击中 查询数据库
            shop = getById(id);

            // 店铺不存在
            if(shop == null){
                log.info("店铺数据库查询为空:{}",id);
                stringRedisTemplate.opsForValue().set(keyShop,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            shopJson = JSONUtil.toJsonStr(shop);
            log.info("数据库查询到店铺并写入缓存:{}",id);
            stringRedisTemplate.opsForValue().set(keyShop,shopJson,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch(Exception e){
            throw new RuntimeException();
        }finally {
            unlock(LOCK_SHOP_KEY+id);
        }
        return shop;
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

    /**
     * 获得互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key,"1",LOCK_SHOP_TTL,TimeUnit.MINUTES));
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
