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

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
    @Override
    public Result queryById(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        Shop shop;

        // 缓存击中 返回商铺信息
        if(StrUtil.isNotBlank(shopJson)){
            log.info("商铺缓存击中:{}",id);
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 缓存未击中 查询数据库
        shop = getById(id);

        // 店铺不存在
        if(shop == null){
            log.info("店铺数据库查询为空:{}",id);
            return Result.fail("店铺不存在");
        }
        shopJson = JSONUtil.toJsonStr(shop);
        log.info("数据库查询到店铺并写入缓存:{}",id);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,shopJson);
        return Result.ok(shop);
    }
}
