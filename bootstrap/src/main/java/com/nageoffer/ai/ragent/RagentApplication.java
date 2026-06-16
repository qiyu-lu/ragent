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

package com.nageoffer.ai.ragent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ragent 核心应用启动类
 *
 * 本类是 Ragent 项目的 Spring Boot 应用程序入口点，负责初始化和启动整个应用。
 *
 * 功能说明：
 * 1. @SpringBootApplication: 标注这是一个 Spring Boot 应用，自动开启组件扫描和自动配置
 * 2. @EnableScheduling: 启用定时任务调度功能，支持 @Scheduled 注解
 * 3. @MapperScan: 配置 MyBatis 的 Mapper 接口扫描路径，包括四个主要模块：
 *    - rag.dao.mapper: RAG（检索增强生成）相关的数据访问层
 *    - ingestion.dao.mapper: 数据摄取模块的数据访问层
 *    - knowledge.dao.mapper: 知识库管理模块的数据访问层
 *    - user.dao.mapper: 用户管理模块的数据访问层
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = {
        "com.nageoffer.ai.ragent.rag.dao.mapper",
        "com.nageoffer.ai.ragent.ingestion.dao.mapper",
        "com.nageoffer.ai.ragent.knowledge.dao.mapper",
        "com.nageoffer.ai.ragent.user.dao.mapper"
})
public class RagentApplication {

    /**
     * Spring Boot 应用程序入口方法
     *
     * @param args 命令行参数，通过 args 可以传递应用配置参数
     *
     * 执行流程：
     * 1. SpringApplication.run() 方法启动 Spring Boot 应用
     * 2. 自动扫描并注册所有标注的 Bean
     * 3. 初始化内置 Tomcat 容器
     * 4. 启动定时任务调度器
     * 5. 扫描指定包中的 MyBatis Mapper 接口
     */
    public static void main(String[] args) {
        SpringApplication.run(RagentApplication.class, args);
    }
}
