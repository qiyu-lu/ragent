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

package com.nageoffer.ai.ragent.core.parser.image;

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片文档解析器（PNG / JPG / SVG）：入库期用 VLM 把图片转成「中文描述 + 图中文字 OCR」，产出单个 {@link ImageBlock}
 * <p>
 * 独立上传的图片自身没有可检索文本，直接 embedding {@code ![](url)} 只是噪声、永远召回不到，故 description 进
 * embedding 负责召回，原图上传资产桶后由 {@link com.nageoffer.ai.ragent.core.chunk.blockaware.ImageChunker}
 * 渲染为 {@code ![caption](url)} 随答复展示；只认领精确 MIME 而不用 {@code image/*} 通配，未覆盖的格式显式报错
 */
@Slf4j
@Component
public class ImageDocumentParser implements DocumentParser {

    public static final String OPT_SOURCE_FILE = "sourceFile";
    public static final String OPT_DOCUMENT_ID = "documentId";

    private final ImageAssetProcessor imageAssetProcessor;

    public ImageDocumentParser(ImageAssetProcessor imageAssetProcessor) {
        this.imageAssetProcessor = imageAssetProcessor;
    }

    @Override
    public String getParserType() {
        return ParserType.IMAGE.getType();
    }

    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(ParseProfile.FAST, Set.of(
                "image/png",
                "image/jpeg",
                "image/jpg",
                "image/svg+xml"
        ));
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            throw new ServiceException("图片解析输入字节为空");
        }
        String sourceFile = extract(options, OPT_SOURCE_FILE, "");
        String documentId = extract(options, OPT_DOCUMENT_ID, java.util.UUID.randomUUID().toString());

        // 0. SVG 归一化：矢量 XML 栅格化成 PNG，此后字节与 mime 与 PNG 路径完全一致
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).equals("image/svg+xml")) {
            content = rasterizeSvg(content);
            mimeType = "image/png";
        }

        // 1. 图生文并上传资产：独立图片和 Excel 内嵌图片共用同一处理入口
        String caption = stripExt(sourceFile);
        ImageBlock block = imageAssetProcessor.process(
                content, mimeType, documentId, Provenance.ofFile(sourceFile), caption, null);

        log.info("图片图生文完成: file={}, descChars={}", sourceFile, block.description().length());
        return ParsedDocument.of(List.of(block), Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "descriptionChars", block.description().length()
        ));
    }

    private static String extract(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? defaultValue : v.toString();
    }

    /**
     * SVG 栅格化成 PNG 字节，VLM 视觉输入只认栅格格式
     * <p>
     * 必须铺白底：PNGTranscoder 默认透明背景，VLM 解码带 alpha 的 PNG 会把透明区合成为黑或空、返回空描述；
     * 无内在尺寸的 SVG 设宽度上限避免超大画布
     */
    private static byte[] rasterizeSvg(byte[] svg) {
        try {
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_WIDTH, 1600f);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(svg)), new TranscoderOutput(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new ServiceException("SVG 栅格化失败：" + e.getMessage());
        }
    }

    private static String stripExt(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
