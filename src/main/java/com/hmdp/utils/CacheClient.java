package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


@Component
@Slf4j
public class CacheClient {

   private final StringRedisTemplate stringRedisTemplate;

   public CacheClient(StringRedisTemplate stringRedisTemplate){
      this.stringRedisTemplate = stringRedisTemplate;
   }

   private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

   /**
    * 设置Redis key-value + 过期时间
    * @param key
    * @param value
    * @param time
    * @param timeUnit
    */
   public void set(String key, Object value, Long time, TimeUnit timeUnit){
      stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
   }

   /**
    * 设置Redis 逻辑过期
    * @param key
    * @param value
    * @param time
    * @param timeUnit
    */
   public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
      RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)),value);
      stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
   }

   /**
    * 缓存穿透
    * @param keyPrefix
    * @param id
    * @param type
    * @param time
    * @param timeUnit
    * @param dbFallBack
    * @return
    * @param <R>
    * @param <ID>
    */
   public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                        Long time,TimeUnit timeUnit,Function<ID,R> dbFallBack)
   {
      String key = keyPrefix+id;
      String json = stringRedisTemplate.opsForValue().get(key);

      // 缓存击中 返回
      if(StrUtil.isNotBlank(json)){
         log.info("CacheClient:queryWithPassThrough 缓存击中:{}",id);
         return JSONUtil.toBean(json, type);
      }
      // 击中空缓存
      if(json!=null){
         log.info("CacheClient:queryWithPassThrough 击中空缓存:{}",id);
         return null;
      }
      // 缓存不存在
      R r= dbFallBack.apply(id);
      // 店铺不存在
      if(r == null){
         json = "";
      }else{
         json = JSONUtil.toJsonStr(r);
      }

      stringRedisTemplate.opsForValue().set(key, json, time, timeUnit);
      return r;
   }

   public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id, Class<R> type,
                                          Long time,TimeUnit timeUnit,Function<ID,R> dbFallBack){
      // 1. 获取Redis数据
      String key = keyPrefix + id;
      String json = stringRedisTemplate.opsForValue().get(key);
      if(StrUtil.isBlank(json)){
         // 未命中
         log.info("CaCheClient:queryWithLogicalExpire 未命中:{}",id);
         return null;
      }
      // 2. 获取对象
      RedisData redisData = JSONUtil.toBean(json, RedisData.class);
      R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
      LocalDateTime expireTime = redisData.getExpireTime();
      log.info("CacheClient:queryWithLogicalExpire 获取缓存信息:{}",r);
      // 3. 未超时
      if(expireTime.isAfter(LocalDateTime.now())){
         log.info("CacheClient:queryWithLogicalExpire 未超时 返回:{}",id);
         return r;
      }
      // 4. 超时 尝试获取互斥锁
      boolean lock = tryLock(LOCK_SHOP_KEY+id);
      log.info("queryWithLogicalExpire 获取锁:{}",lock);
      if(lock){
         // 5.获取成功 双重检查
         json = stringRedisTemplate.opsForValue().get(key);
         redisData = JSONUtil.toBean(json, RedisData.class);
         r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
         log.info("queryWithLogicalExpire 双重检查");
         expireTime = redisData.getExpireTime();
         if(expireTime.isAfter(LocalDateTime.now())){
            log.info("queryWithLogicalExpire 双重检查未超时 返回:{}",id);
            return r;
         }
         // 5.2 双重检查未通过 开启新线程重写缓存
         CACHE_REBUILD_EXECUTOR.submit(()->{
            try{
               // 重建缓存
               R r1= dbFallBack.apply(id);
               setWithLogicalExpire(key,r1,time,timeUnit);
            } catch (Exception e) {
               throw new RuntimeException(e);
            }finally {
               // 释放锁
               unlock(LOCK_SHOP_KEY+id);
            }
         });
      }
      // 6. 返回旧缓存信息

      return r;
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
