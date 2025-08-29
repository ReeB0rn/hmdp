package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1. 获取token
        String token = request.getHeader("authorization");
        String keyToken =LOGIN_USER_KEY + token;
        log.info("拦截器:获取Token:{}",token);
        if(token == null){
            log.info("token异常");
            return true;
        }

        // 2. 判断用户是否存在
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(keyToken);
        if (userMap.isEmpty()) {
            log.info("token获取用户异常:{}",userMap);
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        // 3. 存在 存入ThreadLocal
        UserHolder.saveUser(userDTO);
        // 4. 刷新Token有效期
        stringRedisTemplate.expire(keyToken,LOGIN_USER_TTL, TimeUnit.SECONDS);
        log.info("拦截器:刷新Token有效期");
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
