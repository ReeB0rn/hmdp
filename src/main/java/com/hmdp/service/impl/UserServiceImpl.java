package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {

        // 1. 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            log.info("UserMapper:sendCode 无效手机号");
            return Result.fail("无效手机号");
        }

        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        log.info("UserMapper:sendCode 生成验证码:{}",code);
        // 3. 保存验证码至Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code, LOGIN_CODE_TTL,TimeUnit.MINUTES);
        // 4. 发送验证码
        log.debug("生成验证码:{}",code);
        // 5. 返回
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("无效手机号");
        }

        // 2. 校验验证码
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        if(cacheCode==null ||!cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }

        // 3. 查询用户
        User user = query().eq("phone",phone).one();


        // 4. 不存在则创建
        if(user == null){
            user= createUserWithPhone(phone);
            log.info("UserMapper:login 手机号:{}用户不存在 创建新用户",phone);
        }


        // 5. 保存至Redis
        // 5.1 生成UUID作为TOKEN
        String token = UUID.randomUUID().toString(true);
        String keyToken = LOGIN_USER_KEY+token;
        // 5.2 TOKEN为key 存入Hash
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user,userDTO);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(keyToken,stringObjectMap);
        // 5.3 设置有效时间
        stringRedisTemplate.expire(keyToken,LOGIN_USER_TTL,TimeUnit.SECONDS);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(6));
        save(user);
        return user;
    }
}
