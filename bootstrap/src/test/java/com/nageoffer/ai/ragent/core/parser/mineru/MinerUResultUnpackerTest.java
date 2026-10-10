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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.nageoffer.ai.ragent.core.parser.image.ImageParseProperties;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MinerUResultUnpackerTest {

    private static byte[] zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("full.md"));
            zip.write("# 7 分析步骤\n\n![](images/a.jpg)\n\n称取 0.5000 g 试样。\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("images/a.jpg"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void previewLeavesImagesUnuploadedAndUndescribed() throws IOException {
        FileStorageService storage = mock(FileStorageService.class);
        VlmService vlm = mock(VlmService.class);
        MinerUResultUnpacker unpacker = new MinerUResultUnpacker(storage, vlm, new ImageParseProperties());

        ParsedDocument preview = unpacker.unpack(zip(), "x.pdf", "doc-1", false);

        verifyNoInteractions(storage, vlm);
        ImageBlock image = (ImageBlock) preview.blocks().get(1);
        assertEquals("images/a.jpg", image.asset().publicUrl());
        assertNull(image.description());
        assertEquals(0, preview.metadata().get("imagesUploaded"));
    }

    @Test
    void completeUploadsAndDescribesEachImageOnce() throws IOException {
        FileStorageService storage = mock(FileStorageService.class);
        VlmService vlm = mock(VlmService.class);
        when(storage.uploadAsset(any(), anyString(), anyString()))
                .thenReturn(StoredFileDTO.builder().url("s3://ragent-assets/assets/doc-1/a.jpg").build());
        when(storage.getPublicUrl("s3://ragent-assets/assets/doc-1/a.jpg")).thenReturn("http://assets/doc-1/a.jpg");
        when(vlm.describeImage(any(), anyString(), anyString(), anyInt())).thenReturn("流程图");
        MinerUResultUnpacker unpacker = new MinerUResultUnpacker(storage, vlm, new ImageParseProperties());

        ParsedDocument complete = unpacker.unpack(zip(), "x.pdf", "doc-1");

        verify(storage, times(1)).uploadAsset(any(), anyString(), anyString());
        verify(vlm, times(1)).describeImage(any(), anyString(), anyString(), anyInt());
        ImageBlock image = (ImageBlock) complete.blocks().get(1);
        assertEquals("http://assets/doc-1/a.jpg", image.asset().publicUrl());
        assertEquals("流程图", image.description());
    }
}
