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

package com.nageoffer.ai.ragent.knowledge.support;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从文件名提取稳定文档键与显式版本。没有版本号的普通文档仍以去扩展名后的名称作为文档键。
 */
public final class DocumentIdentityResolver {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?i)(V\\d+(?:\\.\\d+)*(?:-demo)?)");

    private DocumentIdentityResolver() {
    }

    public static DocumentIdentity resolve(String filename) {
        String baseName = stripExtension(filename == null ? "" : filename.trim());
        Matcher matcher = VERSION_PATTERN.matcher(baseName);
        String version = null;
        int versionStart = -1;
        while (matcher.find()) {
            version = normalizeVersion(matcher.group(1));
            versionStart = matcher.start();
        }
        String documentKey = versionStart >= 0
                ? baseName.substring(0, versionStart).replaceFirst("[\\s._-]+$", "")
                : baseName;
        if (documentKey.isBlank()) {
            documentKey = baseName;
        }
        return new DocumentIdentity(documentKey, version,
                version != null && version.toLowerCase(Locale.ROOT).contains("demo"));
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        return "V" + version.substring(1);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    public record DocumentIdentity(String documentKey, String documentVersion, boolean demoData) {
    }
}
