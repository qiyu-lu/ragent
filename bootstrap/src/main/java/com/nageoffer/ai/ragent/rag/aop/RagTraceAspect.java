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

package com.nageoffer.ai.ragent.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.framework.trace.RagTraceRoot;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * 注解式 RAG Trace 采集切面
 * <p>
 * 拦截两类注解：
 * - {@code @RagTraceRoot}：一次完整请求的入口方法，创建 TraceRun 记录并初始化线程上下文
 * - {@code @RagTraceNode}：RAG 内部各执行阶段（意图解析、改写、检索等），创建 TraceNode 记录
 * <p>
 * 切面顺序 {@code HIGHEST_PRECEDENCE + 10}，确保在大多数业务切面之前执行。
 * 全局开关 {@code rag.trace.enabled=false} 时切面直接透传，不做任何追踪。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RagTraceAspect {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final RagTraceRecordService traceRecordService;
    private final RagTraceProperties traceProperties;

    /**
     * 拦截 @RagTraceRoot 方法，负责链路的开始与结束
     * <p>
     * 幂等保护：若当前线程已存在 traceId（嵌套调用场景），直接透传不重复创建链路
     */
    @Around("@annotation(traceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint, RagTraceRoot traceRoot) throws Throwable {
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }

        String existingTraceId = RagTraceContext.getTraceId();
        if (StrUtil.isNotBlank(existingTraceId)) {
            // 当前线程已在链路中，避免重复创建 root（防止方法嵌套调用时产生多条 TraceRun）
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        // 生成全局唯一链路 ID（雪花 ID）
        String traceId = IdUtil.getSnowflakeNextIdStr();
        // 从方法参数中按名称提取 conversationId / taskId（参数名在 @RagTraceRoot 中配置）
        String conversationId = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.conversationIdArg());
        String taskId = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.taskIdArg());
        // 链路名称：优先使用注解配置，缺省时退化为方法名
        String traceName = StrUtil.blankToDefault(traceRoot.name(), method.getName());
        Date startTime = new Date();
        long startMillis = System.currentTimeMillis();

        // 写入 RUNNING 状态的 TraceRun，endTime/durationMs 在结束时补充
        traceRecordService.startRun(RagTraceRunDO.builder()
                .traceId(traceId)
                .traceName(traceName)
                .entryMethod(method.getDeclaringClass().getName() + "#" + method.getName())
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(UserContext.getUserId())
                .status(STATUS_RUNNING)
                .startTime(startTime)
                .build());

        // 将 traceId 写入 TTL（TransmittableThreadLocal），异步线程池中也可取到
        RagTraceContext.setTraceId(traceId);
        try {
            Object result = joinPoint.proceed();
            // 正常结束：更新状态为 SUCCESS
            traceRecordService.finishRun(
                    traceId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            return result;
        } catch (Throwable ex) {
            // 异常结束：更新状态为 ERROR，记录截断后的错误信息
            traceRecordService.finishRun(
                    traceId,
                    STATUS_ERROR,
                    truncateError(ex),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            throw ex;
        } finally {
            // 无论成功或失败，清理 TTL 防止线程复用时数据泄露
            RagTraceContext.clear();
        }
    }

    /**
     * 拦截 @RagTraceNode 方法，负责单个执行节点的记录
     * <p>
     * 若当前线程无 traceId（未在链路上下文中），直接透传不记录节点
     */
    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }
        String traceId = RagTraceContext.getTraceId();
        if (StrUtil.isBlank(traceId)) {
            // 不在任何链路上下文中（如单元测试或直接调用），跳过追踪
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        // 从节点栈顶取当前父节点 ID（null 表示此节点是 root 的直接子节点）
        String parentNodeId = RagTraceContext.currentNodeId();
        // 深度 = 节点栈大小，调用链越深值越大
        int depth = RagTraceContext.depth();
        Date startTime = new Date();
        long startMillis = System.currentTimeMillis();

        // 写入 RUNNING 状态的 TraceNode
        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .parentNodeId(parentNodeId)
                .depth(depth)
                // type 缺省为 "METHOD"，name 缺省为方法名
                .nodeType(StrUtil.blankToDefault(traceNode.type(), "METHOD"))
                .nodeName(StrUtil.blankToDefault(traceNode.name(), method.getName()))
                .className(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .status(STATUS_RUNNING)
                .startTime(startTime)
                .build());

        // 将当前节点 ID 压栈，后续嵌套节点可通过 currentNodeId() 取到它作为 parentNodeId
        RagTraceContext.pushNode(nodeId);
        try {
            Object result = joinPoint.proceed();
            traceRecordService.finishNode(
                    traceId, nodeId, STATUS_SUCCESS, null,
                    new Date(), System.currentTimeMillis() - startMillis
            );
            return result;
        } catch (Throwable ex) {
            traceRecordService.finishNode(
                    traceId, nodeId, STATUS_ERROR, truncateError(ex),
                    new Date(), System.currentTimeMillis() - startMillis
            );
            throw ex;
        } finally {
            // 方法返回后弹出节点栈，恢复父节点为当前节点
            RagTraceContext.popNode();
        }
    }

    /**
     * 按参数名从方法签名中提取字符串类型的参数值
     * 用于从入口方法参数里提取 conversationId / taskId
     */
    private String resolveStringArg(MethodSignature signature, Object[] args, String argName) {
        if (StrUtil.isBlank(argName) || args == null || args.length == 0) {
            return null;
        }
        String[] parameterNames = signature.getParameterNames();
        if (parameterNames == null || parameterNames.length != args.length) {
            return null;
        }
        for (int i = 0; i < parameterNames.length; i++) {
            if (!argName.equals(parameterNames[i])) {
                continue;
            }
            Object arg = args[i];
            if (arg == null) {
                return null;
            }
            return String.valueOf(arg);
        }
        return null;
    }

    /**
     * 截取异常信息至 maxErrorLength，防止超长错误文本写入数据库
     * 格式：简单类名 + ": " + message
     */
    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        if (message.length() <= traceProperties.getMaxErrorLength()) {
            return message;
        }
        return message.substring(0, traceProperties.getMaxErrorLength());
    }
}
