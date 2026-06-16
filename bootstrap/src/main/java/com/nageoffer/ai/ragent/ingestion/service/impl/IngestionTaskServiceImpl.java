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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据摄入任务服务实现
 */
@Service
@RequiredArgsConstructor
public class IngestionTaskServiceImpl implements IngestionTaskService {

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        // 将请求中的 DocumentSourceRequest 转换为领域对象 DocumentSource，并校验 type 非空
        DocumentSource source = toSource(request.getSource());
        // rawBytes/mimeType 为 null 表示由 fetcher 节点自行从远程获取文档内容
        return executeInternal(request.getPipelineId(), source, null, null, request.getVectorSpaceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("文件不能为空"));
        try {
            // 一次性读取文件字节，不落磁盘，直接传入流水线内存处理
            byte[] bytes = file.getBytes();
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                fileName = "upload.bin";
            }
            // 通过魔数（magic bytes）自动检测 MIME 类型，优先于 Content-Type 头
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            // 上传场景下 vectorSpaceId 为 null，由流水线 indexer 节点配置决定写入目标
            return executeInternal(pipelineId, source, bytes, mimeType, null);
        } catch (Exception e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        }
    }

    @Override
    public IngestionTaskVO get(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("未找到任务"));
        return toVO(task);
    }

    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status) {
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> qw = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, qw);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        LambdaQueryWrapper<IngestionTaskNodeDO> qw = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(qw);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    /**
     * 摄入任务执行核心方法，由 execute() 和 upload() 共同调用。
     *
     * @param pipelineId    流水线 ID（必填）
     * @param source        文档来源描述（类型、位置、文件名）
     * @param rawBytes      文件字节（upload 场景提供；execute 场景为 null，由 fetcher 节点自行拉取）
     * @param mimeType      MIME 类型（upload 场景由魔数检测；execute 场景为 null）
     * @param vectorSpaceId 目标向量空间（可为 null，由 indexer 节点配置决定）
     */
    private IngestionResult executeInternal(String pipelineId,
                                            DocumentSource source,
                                            byte[] rawBytes,
                                            String mimeType,
                                            VectorSpaceId vectorSpaceId) {
        // 校验并获取流水线定义（含节点链配置）
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        PipelineDefinition pipeline = pipelineService.getDefinition(resolvedPipelineId);

        // 创建任务记录，初始状态为 RUNNING，startedAt 记录引擎启动时间
        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(resolvedPipelineId)
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        taskMapper.insert(task);

        // 构建流水线执行上下文，贯穿所有节点传递状态（rawBytes/mimeType 仅 upload 场景非 null）
        IngestionContext context = IngestionContext.builder()
                .taskId(String.valueOf(task.getId()))
                .pipelineId(resolvedPipelineId)
                .source(source)
                .rawBytes(rawBytes)
                .mimeType(mimeType)
                .vectorSpaceId(vectorSpaceId)
                .logs(new ArrayList<>())
                .build();

        // 同步执行整条流水线（阻塞直至最后一个节点完成或失败）
        IngestionContext result = engine.execute(pipeline, context);

        // 持久化各节点运行日志到 t_ingestion_task_node
        saveNodeLogs(task, pipeline, result.getLogs());

        // 将执行结果（最终状态、chunkCount、错误信息、日志摘要）写回 t_ingestion_task
        updateTaskFromContext(task, result);

        return IngestionResult.builder()
                .taskId(result.getTaskId())
                .pipelineId(result.getPipelineId())
                .status(result.getStatus())
                .chunkCount(result.getChunks() == null ? 0 : result.getChunks().size())
                // 成功时 message 固定为 "OK"，失败时为异常消息
                .message(result.getError() == null ? "OK" : result.getError().getMessage())
                .build();
    }

    /**
     * 流水线执行完成后，将上下文中的结果回写到任务主记录。
     * logsJson 存的是去除了 output 的日志摘要（节点详细输出存 t_ingestion_task_node）。
     */
    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context) {
        // status 为 null 表示引擎异常退出，降级为 FAILED
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(UserContext.getUsername());
        // 日志摘要：剔除 output 字段，避免 logs_json 过大
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        // 元数据：合并 context.metadata + keywords + questions
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    /**
     * 将引擎执行过程中每个节点的运行日志持久化到 t_ingestion_task_node。
     * nodeOrder 由 buildNodeOrderMap() 根据流水线 nextNodeId 链推算，用于前端排序展示。
     * outputJson 超过 1MB 时会被截断，防止超出数据库 max_allowed_packet。
     */
    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        // 预先构建 nodeId → 执行顺序编号的映射
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            // 根据 log.success 和 message 前缀推断节点状态（success/failed/skipped）
            String status = resolveNodeStatus(log);
            // 截断大尺寸 output，避免超出 DB 字段限制
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    /**
     * 根据流水线定义推算每个节点的执行顺序编号（从 1 开始）。
     * 算法：找到"未被任何节点的 nextNodeId 引用"的节点作为起点，
     * 沿 nextNodeId 链遍历赋值；最后对孤立节点（若有）补充编号。
     */
    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        // 去重并保留插入顺序
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        // 收集所有被 nextNodeId 引用过的节点 ID（非起点）
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        // 从未被引用的节点（即起点）开始沿链赋值顺序编号
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        // 处理未被上述链遍历覆盖的孤立节点（正常流水线不应存在）
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    private String resolveNodeStatus(NodeLog log) {
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    private String resolvePipelineId(String pipelineId) {
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        throw new ClientException("必须传流水线ID");
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    private DocumentSource toSource(DocumentSourceRequest request) {
        Assert.notNull(request, () -> new ClientException("文档来源不能为空"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials())
                .build();
        if (source.getType() == null) {
            throw new ClientException("文档来源类型不能为空");
        }
        return source;
    }

    private IngestionTaskVO toVO(IngestionTaskDO task) {
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .status(normalizeStatus(task.getStatus()))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(BeanUtil.beanToMap(task.getMetadataJson()))
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(BeanUtil.beanToMap(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    private String normalizeNodeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 截断过大的输出 JSON，防止超过数据库的 max_allowed_packet 限制
     * 默认限制为 1MB
     */
    private String truncateOutputJson(Object output) {
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        // 限制为 1MB (1,048,576 字节)，留有余量避免接近 4MB 上限
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        // 截断并添加提示信息
        String truncated = json.substring(0, maxSize - 100);
        return truncated + "... [输出过大，已截断，原始大小: " + json.length() + " 字节]";
    }
}
