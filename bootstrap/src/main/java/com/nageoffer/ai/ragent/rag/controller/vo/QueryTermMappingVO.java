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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 关键词映射视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueryTermMappingVO {

    /** 映射规则主键 ID */
    private String id;

    /** 用户原始短语（查询中需要被替换的词） */
    private String sourceTerm;

    /** 归一化后的目标短语（替换为标准词） */
    private String targetTerm;

    /**
     * 匹配类型：
     * 1=精确匹配（当前仅实现此类型）
     * 2=前缀匹配
     * 3=正则匹配
     * 4=整词匹配
     */
    private Integer matchType;

    /** 优先级，数值越小优先级越高；长词建议设置更小的值以避免短词先行替换打断长词 */
    private Integer priority;

    /** 是否生效：true=启用，false=禁用 */
    private Boolean enabled;

    /** 备注说明 */
    private String remark;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
