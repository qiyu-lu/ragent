/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.idempotent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 防止用户重复提交表单信息切面控制器
 */
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentSubmitAspect {

    private final RedissonClient redissonClient;
    private final Gson gson = new Gson();

    /**
     * 增强方法标记 {@link IdempotentSubmit} 注解逻辑
     */
    //拦截所有加了 @IdempotentSubmit 的方法 只要某个方法上标了 @IdempotentSubmit 注解，就进入这个 AOP 环绕逻辑。
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit)")
    public Object idempotentSubmit(ProceedingJoinPoint joinPoint) throws Throwable {
        //从当前被拦截的方法上拿到注解对象
        IdempotentSubmit idempotentSubmit = getIdempotentSubmitAnnotation(joinPoint);
        // 获取分布式锁标识
        String lockKey = buildLockKey(joinPoint, idempotentSubmit);
        //通过 Redisson 获取分布式锁
        RLock lock = redissonClient.getLock(lockKey);
        // 尝试获取锁，获取锁失败就意味着已经重复提交，直接抛出异常
        //尝试加锁
        if (!lock.tryLock()) {
            //如果失败，说明这个 key 对应的锁已经被其他请求占用了，也就是当前用户已经有一个相同类型的请求正在处理中。
            throw new ClientException(idempotentSubmit.message());
        }
        Object result;
        try {
            // 执行标记了防重复提交注解的方法原逻辑
            //这一行是真正执行被拦截的方法。
            result = joinPoint.proceed();
        } finally {
            //无论接口正常返回，还是执行过程中抛异常，最后都会释放锁
            lock.unlock();
        }
        return result;
    }

    /**
     * @return 返回自定义防重复提交注解
     */
    public static IdempotentSubmit getIdempotentSubmitAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentSubmit.class);
    }

    /**
     * @return 获取当前线程上下文 ServletPath
     */
    private String getServletPath() {
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return Objects.requireNonNull(sra).getRequest().getServletPath();
    }

    /**
     * @return 当前操作用户 ID
     */
    private String getCurrentUserId() {
        return UserContext.getUserId();
    }

    /**
     * @return joinPoint md5
     */
    private String calcArgsMD5(ProceedingJoinPoint joinPoint) {
        return DigestUtil.md5Hex(gson.toJson(joinPoint.getArgs()).getBytes(StandardCharsets.UTF_8));
    }

    //生成 Redisson 分布式锁的 key,判断“哪些请求算重复请求”的依据
    private String buildLockKey(ProceedingJoinPoint joinPoint, IdempotentSubmit idempotentSubmit) {
        //判断注解里有没有自定义 key,如果你在注解里写了 key，就优先使用这个 key
        if (StrUtil.isNotBlank(idempotentSubmit.key())) {
            //获取当前被拦截的方法签名
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            //解析 SpEL 表达式，之前写的key不是普通的字符串，而是SpEL表达式，T(...) 是 SpEL 里调用静态类的语法
            //得到spel中的含义返回值，即userid
            Object keyValue = SpELUtil.parseKey(idempotentSubmit.key(), signature.getMethod(), joinPoint.getArgs());
            //生成自定义锁 key,但是有一个问题，如果多个接口都用了同样的key，即相同的 key= "T(...)"
            //那么它们会共用类似的下面的这个格式，这样会导致同一个用户访问接口 A 时，接口 B 也可能被挡住。
            return String.format("idempotent-submit:key:%s", keyValue);
        }
        //如果注解没有写 key，走默认逻辑
        return String.format(
                //请求路径 + 当前用户 ID + 参数 MD5
                "idempotent-submit:path:%s:currentUserId:%s:md5:%s",
                //防止重复： 同用户、同接口、同参数
                getServletPath(),
                getCurrentUserId(),
                calcArgsMD5(joinPoint)
        );
    }
}
