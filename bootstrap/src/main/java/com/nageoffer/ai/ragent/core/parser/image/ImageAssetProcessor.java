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

import com.nageoffer.ai.ragent.core.parser.model.AssetRef;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * 图片图生文与资产持久化的共享入口，供独立图片解析器和 Excel 内嵌图片复用。
 */
@Component
@RequiredArgsConstructor
public class ImageAssetProcessor {

    private final VlmService vlmService;
    private final FileStorageService fileStorageService;
    private final ImageParseProperties properties;

    public ImageBlock process(byte[] content,
                              String mimeType,
                              String documentId,
                              Provenance provenance,
                              String caption,
                              String prompt) {
        String effectivePrompt = prompt == null || prompt.isBlank()
                ? properties.getDescriptionPrompt()
                : prompt;
        String description = vlmService.describeImage(
                content, mimeType, effectivePrompt, properties.getMaxOutputTokens());
        description = description == null ? "" : description.strip();
        if (description.isBlank()) {
            throw new ServiceException("VLM 返回空描述，无法生成可检索文本：" + caption);
        }

        String filename = "assets/" + documentId + "/" + UUID.randomUUID() + "." + extFromMime(mimeType);
        StoredFileDTO stored = fileStorageService.uploadAsset(content, filename, mimeType);
        String publicUrl = fileStorageService.getPublicUrl(stored.getUrl());
        AssetRef asset = new AssetRef(publicUrl, mimeType);
        return new ImageBlock(provenance, asset, caption, caption, description);
    }

    private static String extFromMime(String mimeType) {
        if (mimeType == null) {
            return "png";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }
}
