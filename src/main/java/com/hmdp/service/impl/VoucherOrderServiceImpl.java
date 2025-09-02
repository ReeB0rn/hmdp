package com.hmdp.service.impl;

import com.baomidou.mybatisplus.annotation.TableField;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {

        // 1. 获取秒杀券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        log.info("VoucherOrderService:seckillVoucher: 秒杀券信息:{}",voucher);
        if(voucher == null){
            log.info("VoucherOrderService:seckillVoucher: 秒杀券不存在 ID:{}",voucherId);
            return Result.fail("秒杀券不存在");
        }
        // 2. 判断秒杀券活动时间
        // 2.1 判断是否开始
        if(LocalDateTime.now().isBefore(voucher.getBeginTime())){
            log.info("VoucherOrderService:seckillVoucher: 活动尚未开始:{}",voucher.getBeginTime());
            return Result.fail("活动尚未开始");
        }
        // 2.2 判断是否结束
        if(LocalDateTime.now().isAfter(voucher.getEndTime())){
            log.info("VoucherOrderService:seckillVoucher: 活动已经结束:{}",voucher.getEndTime());
            return Result.fail("活动已经结束");
        }
        // 3. 判断库存
        if(voucher.getStock()<1){
            log.info("VoucherOrderService:seckillVoucher: 库存不足");
            return Result.fail("库存不足");
        }
        // 4. 更新秒杀券信息
//        voucher.setStock(voucher.getStock()-1);
//        boolean success = seckillVoucherMapper.update(voucher);
//        if(!success){
//            log.info("VoucherOrderService:seckillVoucher: 更新失败");
//            return Result.fail("更新失败");
//        }]
        Long userId = UserHolder.getUser().getId();
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order"+userId,stringRedisTemplate);
        boolean lock = simpleRedisLock.tryLock(1200);
        if(!lock){
            return Result.fail("同时只能下一单");
        }
        try{
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            simpleRedisLock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("voucher_id", voucherId).eq("user_id",userId).count();
        if(count > 0 ){
            return Result.fail("用户已经购买过一次");
        }
        boolean success = seckillVoucherService.update().
                setSql("stock = stock - 1").
                eq("voucher_id", voucherId).gt("stock",0).
                update();
        if(!success){
            log.info("VoucherOrderService:seckillVoucher: 更新失败");
            return Result.fail("更新失败");
        }
        // 5. 生成订单信息
        long id = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(id);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        save(voucherOrder);

        return Result.ok();
    }
}
