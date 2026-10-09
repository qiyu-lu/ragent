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

package com.nageoffer.ai.ragent.rag.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评测模式配置
 * <p>
 * {@code ragent.eval.enabled} 同时被框架层的 {@code IdempotentSubmitAspect} 读取（为 true 时跳过防重锁），
 * 这里只负责评测接口自己的参数；生产环境默认关闭，评测实例通过独立配置文件或 {@code --ragent.eval.enabled=true} 开启
 */
@Data
@Component
@ConfigurationProperties(prefix = "ragent.eval")
public class EvalProperties {

    /**
     * 是否注册评测接口 {@code POST /rag/eval/replay}
     */
    private boolean enabled = false;

    /**
     * 首次改写（请求未带 subQuestions）时，把原问题与改写出的子问题按 JSONL 追加到这个文件，供后续回放；
     * 留空则不落盘。路径相对应用工作目录
     */
    private String rewriteLog = "local-data/kq-eval/rewrites/rewrites.jsonl";

    /**
     * 回放请求允许的子问题个数上限
     */
    private int maxSubQuestions = 10;
}
