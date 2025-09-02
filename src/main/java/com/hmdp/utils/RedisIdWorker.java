package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1735689600L;

    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){

        // 1. 获取当前时间的时间戳
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)-BEGIN_TIMESTAMP;

        // 2. 获取序列号
        // 2.1 获取当前日期
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 获取自增ID
        long id = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);
        // 3. 返回
        return now<<COUNT_BITS|id;
    }
}
