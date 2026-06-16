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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueryTermMappingAdminServiceImpl implements QueryTermMappingAdminService {

    private final QueryTermMappingMapper queryTermMappingMapper;
    private final QueryTermMappingService queryTermMappingService;

    @Override
    public String create(QueryTermMappingCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));

        // trim 去除首尾空白，并在为空时设为 null，再断言非 null/blank
        String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
        String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
        Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
        Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));

        // 构建实体，设置默认值：matchType=1（精确匹配），priority=0，enabled=1（生效）
        QueryTermMappingDO record = new QueryTermMappingDO();
        record.setSourceTerm(sourceTerm);
        record.setTargetTerm(targetTerm);
        record.setMatchType(requestParam.getMatchType() != null ? requestParam.getMatchType() : 1);
        record.setPriority(requestParam.getPriority() != null ? requestParam.getPriority() : 0);
        record.setEnabled(requestParam.getEnabled() != null ? (requestParam.getEnabled() ? 1 : 0) : 1);
        record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));

        queryTermMappingMapper.insert(record);

        // 写库后立即重载内存缓存，新规则无需重启即可生效
        queryTermMappingService.loadMappings();
        return String.valueOf(record.getId());
    }

    @Override
    public void update(String id, QueryTermMappingUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));

        // 校验规则存在
        QueryTermMappingDO record = loadById(id);

        // Patch 语义：所有字段仅在非 null 时才更新
        // sourceTerm/targetTerm 传值后 trim，且 trim 后不能为空（防止设置为纯空白词）
        if (requestParam.getSourceTerm() != null) {
            String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
            Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
            record.setSourceTerm(sourceTerm);
        }
        if (requestParam.getTargetTerm() != null) {
            String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
            Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));
            record.setTargetTerm(targetTerm);
        }
        if (requestParam.getMatchType() != null) {
            record.setMatchType(requestParam.getMatchType());
        }
        if (requestParam.getPriority() != null) {
            record.setPriority(requestParam.getPriority());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled() ? 1 : 0);
        }
        // remark 传 "" 时 trimToNull 返回 null，可清空备注；null 不更新
        if (requestParam.getRemark() != null) {
            record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));
        }

        queryTermMappingMapper.updateById(record);

        // 更新后立即重载内存缓存，变更即刻生效
        queryTermMappingService.loadMappings();
    }

    @Override
    public void delete(String id) {
        // 校验规则存在后物理删除（t_query_term_mapping 无逻辑删除字段）
        QueryTermMappingDO record = loadById(id);
        queryTermMappingMapper.deleteById(record.getId());

        // 删除后立即重载内存缓存，规则即刻失效
        queryTermMappingService.loadMappings();
    }

    @Override
    public QueryTermMappingVO queryById(String id) {
        QueryTermMappingDO record = loadById(id);
        return toVO(record);
    }

    @Override
    public IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam) {
        // keyword trim 后为 null 则不加过滤条件，返回全部规则
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<QueryTermMappingDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<QueryTermMappingDO> result = queryTermMappingMapper.selectPage(
                page,
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        // keyword 非空时：sourceTerm LIKE '%keyword%' OR targetTerm LIKE '%keyword%'
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(QueryTermMappingDO::getSourceTerm, keyword)
                                .or()
                                .like(QueryTermMappingDO::getTargetTerm, keyword))
                        // 主排序：priority 升序（数值小的优先级高，排在前面）
                        .orderByAsc(QueryTermMappingDO::getPriority)
                        // 次排序：最近更新的排在前面
                        .orderByDesc(QueryTermMappingDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    private QueryTermMappingDO loadById(String id) {
        QueryTermMappingDO record = queryTermMappingMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("映射规则不存在"));
        return record;
    }

    private QueryTermMappingVO toVO(QueryTermMappingDO record) {
        return QueryTermMappingVO.builder()
                .id(String.valueOf(record.getId()))
                .sourceTerm(record.getSourceTerm())
                .targetTerm(record.getTargetTerm())
                .matchType(record.getMatchType())
                .priority(record.getPriority())
                .enabled(record.getEnabled() != null && record.getEnabled() == 1)
                .remark(record.getRemark())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
