package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    private final static DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("script/seckill.lua"));
    }

    private BlockingQueue<VoucherOrder> orderTaskQueue = new ArrayBlockingQueue<VoucherOrder>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    // 1. 获取消息队列消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofMinutes(2)),
                            StreamOffset.create("stream.order", ReadOffset.lastConsumed())
                    );
                    // 2.判断是否获取成功
                    if(list==null||list().isEmpty()){
                        //获取失败
                        continue;
                    }
                    // 3.获取成功
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4.确认
                    stringRedisTemplate.opsForStream().acknowledge("stream.order","g1",entries.getId());

                } catch (Exception e) {
                    log.error(e.getMessage());
                    handlePandingList();
                }
            }
        }
    }

    private void handlePandingList() {
        while(true){
            try{
                // 1. 获取判定队列
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.order", ReadOffset.from("0"))
                );
                if(list==null||list.isEmpty()){
                    break;
                }
                MapRecord<String, Object, Object> entries = list.get(0);
                Map<Object, Object> value = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge("stream.order","g1",entries.getId());
            }catch(Exception e){
                log.error(e.getMessage());
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean triedLock = lock.tryLock();
        log.info("voucher order getLock:{}",triedLock);
        if(!triedLock){
            log.info("VoucherOrderService:handleVoucherOrder: 用户只能下一单");
            return;
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {


//        // 1. 获取秒杀券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        log.info("VoucherOrderService:seckillVoucher: 秒杀券信息:{}",voucher);
//        if(voucher == null){
//            log.info("VoucherOrderService:seckillVoucher: 秒杀券不存在 ID:{}",voucherId);
//            return Result.fail("秒杀券不存在");
//        }
//        // 2. 判断秒杀券活动时间
//        // 2.1 判断是否开始
//        if(LocalDateTime.now().isBefore(voucher.getBeginTime())){
//            log.info("VoucherOrderService:seckillVoucher: 活动尚未开始:{}",voucher.getBeginTime());
//            return Result.fail("活动尚未开始");
//        }
//        // 2.2 判断是否结束
//        if(LocalDateTime.now().isAfter(voucher.getEndTime())){
//            log.info("VoucherOrderService:seckillVoucher: 活动已经结束:{}",voucher.getEndTime());
//            return Result.fail("活动已经结束");
//        }
//        // 3. 判断库存
//        if(voucher.getStock()<1){
//            log.info("VoucherOrderService:seckillVoucher: 库存不足");
//            return Result.fail("库存不足");
//        }
        // 4. 更新秒杀券信息
//        voucher.setStock(voucher.getStock()-1);
//        boolean success = seckillVoucherMapper.update(voucher);
//        if(!success){
//            log.info("VoucherOrderService:seckillVoucher: 更新失败");
//            return Result.fail("更新失败");
//        }]
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean triedLock = lock.tryLock();
//        if(!triedLock){
//            return Result.fail("同时只能下一单");
//        }
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long success = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString());
        log.info("seckillVoucher success:{}", success);
        if(success.intValue() != 0) {
            return Result.fail(success == 1 ? "库存不足" : "用户已下过一单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok();
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        log.info("createVoucherOrder:{}", voucherOrder);
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        boolean success = seckillVoucherService.update().
                setSql("stock = stock - 1").
                eq("voucher_id", voucherId).gt("stock",0).
                update();
        if(!success){
            log.info("VoucherOrderService:seckillVoucher: 更新失败");
            return;
        }
        // 5. 生成订单信息
        save(voucherOrder);
    }


}
