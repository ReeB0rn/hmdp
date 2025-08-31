package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
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

    public Shop queryWithLogicalExpire(Long id){
        // 1. 获取Redis数据
        String keyShop = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(keyShop);
        if(StrUtil.isBlank(shopJson)){
            // 未命中
            log.info("queryWithLogicalExpire 未命中:{}",id);
            return null;
        }
        // 2. 获取对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        log.info("queryWithLogicalExpire 获取缓存信息:{}",shop);
        // 3. 未超时
        if(expireTime.isAfter(LocalDateTime.now())){
            log.info("queryWithLogicalExpire 未超时 返回:{}",id);
            return shop;
        }
        // 4. 超时 尝试获取互斥锁
        boolean lock = tryLock(LOCK_SHOP_KEY+id);
        log.info("queryWithLogicalExpire 获取锁:{}",lock);
        if(lock){
            // 5.获取成功 双重检查
            shopJson = stringRedisTemplate.opsForValue().get(keyShop);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            log.info("queryWithLogicalExpire 双重检查");
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                log.info("queryWithLogicalExpire 双重检查未超时 返回:{}",id);
                return shop;
            }
            // 5.2 双重检查未通过 开启新线程重写缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    // 重建缓存
                    this.saveShopToRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(LOCK_SHOP_KEY+id);
                }
            });
        }
        // 6. 返回旧缓存信息

        return shop;
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

    public void saveShopToRedis(Long id,Long expireSeconds){

        Shop shop = getById(id);
        String keyShop = CACHE_SHOP_KEY + id;

        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(JSONUtil.toJsonStr(shop));
        stringRedisTemplate.opsForValue().set(keyShop,JSONUtil.toJsonStr(shop));
    }

}
