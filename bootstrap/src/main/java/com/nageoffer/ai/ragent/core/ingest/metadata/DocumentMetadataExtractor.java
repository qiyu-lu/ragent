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

package com.nageoffer.ai.ragent.core.ingest.metadata;

import com.nageoffer.ai.ragent.core.parser.BlockTextRenderer;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.normalize.IngestionTextNormalizer;
import com.nageoffer.ai.ragent.core.parser.quality.PdfTextLayer;
import com.nageoffer.ai.ragent.core.parser.quality.TextLayerClass;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 入库时抽文档元数据：标准号、发布年、代替的旧标准从封面与前言抽，检测对象 / 组分 / 方法用术语表匹配标题
 * <p>
 * 标准号先找 PDF 文字层（文字层可用时数字最可靠：2014 版两份 GB/T 的解析结果里"代替"后面的号整段丢了），
 * 找不到再找解析结果的开头（2007 版全铁的页眉字体在文字层里是乱码"犌犅/犜"）。标题取文件名与解析结果的
 * 前三个标题；抽到的值都是草稿，人工确认后来源改为 confirmed
 */
@Component
@RequiredArgsConstructor
public class DocumentMetadataExtractor {

    private static final String PREFIX = "(GB/T|GB/Z|GB|YS/T|YB/T|HG/T|JB/T|SN/T)";
    /**
     * 年份允许字间空格：YS/T 封面排成"20 11""20 1 1"
     */
    private static final String YEAR = "((?:19|20)\\s?\\d\\s?\\d)(?!\\d)";
    private static final Pattern STANDARD_NO = Pattern.compile(PREFIX + "\\s*(\\d+(?:\\.\\d+)*)\\s*-+\\s*" + YEAR);
    private static final Pattern REPLACES = Pattern.compile("代替\\s*((?:" + PREFIX
            + "\\s*\\d+(?:\\.\\d+)*\\s*-+\\s*(?:19|20)\\s?\\d\\s?\\d(?!\\d)\\s*[、,;]?\\s*)+)");
    private static final int PARSED_HEAD_BLOCKS = 40;
    private static final int TITLE_HEADINGS = 3;

    private final TermDictionary terms;

    /**
     * 规整后的标准号：前缀与编号之间一个空格、年份前一个连字符，如 GB/T 6730.10-2014
     */
    public record StandardNumber(String base, int year) {

        @Override
        public String toString() {
            return base + "-" + year;
        }
    }

    /**
     * 从任意写法（全角、破折号、无空格）里认出第一个标准号；认不出返回 null
     */
    public static StandardNumber parseStandardNo(String text) {
        Matcher matcher = STANDARD_NO.matcher(IngestionTextNormalizer.normalize(text));
        return matcher.find() ? number(matcher) : null;
    }

    private static StandardNumber number(Matcher matcher) {
        return new StandardNumber(matcher.group(1) + " " + matcher.group(2),
                Integer.parseInt(matcher.group(3).replaceAll("\\s", "")));
    }

    public DocumentMetadata extract(String filename, PdfTextLayer textLayer, ParsedDocument parsed) {
        List<String> heads = new ArrayList<>(2);
        if (textLayer != null && textLayer.textLayerClass() == TextLayerClass.TEXT) {
            heads.add(IngestionTextNormalizer.normalize(textLayer.headText()));
        }
        heads.add(IngestionTextNormalizer.normalize(parsedHead(parsed)));

        // 本标准号不能取自"代替"子句：2007 版全铁的页眉在文字层里是乱码，文字层里第一个能读的号是被代替的 1986 版
        StandardNumber number = null;
        for (String head : heads) {
            number = parseStandardNo(REPLACES.matcher(head).replaceAll(" "));
            if (number != null) {
                break;
            }
        }
        String standardNo = number == null ? null : number.toString();

        Set<String> replaces = new LinkedHashSet<>();
        for (String head : heads) {
            Matcher list = REPLACES.matcher(head);
            while (list.find()) {
                Matcher one = STANDARD_NO.matcher(list.group(1));
                while (one.find()) {
                    String replaced = number(one).toString();
                    if (!replaced.equals(standardNo)) {
                        replaces.add(replaced);
                    }
                }
            }
        }

        String title = titleText(filename, parsed);
        return new DocumentMetadata(standardNo, number == null ? null : number.base(),
                number == null ? null : number.year(), List.copyOf(replaces),
                terms.canonicals(title, TermDictionary.OBJECT),
                terms.canonicals(title, TermDictionary.COMPONENT),
                terms.canonicals(title, TermDictionary.METHOD),
                DocumentMetadata.SOURCE_EXTRACTED);
    }

    private static String parsedHead(ParsedDocument parsed) {
        if (parsed == null || parsed.blocks() == null) {
            return "";
        }
        List<Block> blocks = parsed.blocks();
        return BlockTextRenderer.render(blocks.subList(0, Math.min(PARSED_HEAD_BLOCKS, blocks.size())));
    }

    /**
     * 标题文本：去扩展名的文件名 + 解析结果的前几个标题
     */
    private static String titleText(String filename, ParsedDocument parsed) {
        StringBuilder title = new StringBuilder();
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            title.append(dot > 0 ? filename.substring(0, dot) : filename).append('\n');
        }
        if (parsed != null && parsed.blocks() != null) {
            parsed.blocks().stream()
                    .filter(HeadingBlock.class::isInstance)
                    .map(HeadingBlock.class::cast)
                    .limit(TITLE_HEADINGS)
                    .forEach(heading -> title.append(heading.text()).append('\n'));
        }
        return title.toString();
    }
}
