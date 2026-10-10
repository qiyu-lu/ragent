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

import com.nageoffer.ai.ragent.core.parser.normalize.IngestionTextNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 领域术语表（knowledge-quality 计划 §5.3）：术语、同义写法、类别
 * <p>
 * 三处用它：入库时从标题抽文档的检测对象 / 组分 / 方法，检索时识别查询提到的检测对象与组分，
 * 阶段 3 作中文分词的用户词典。两侧都先转成比对形态（NFKC、去空白、ASCII 小写）再做最长匹配，
 * 所以"钛铁矿精矿"不会被拆出"铁矿"、"硫酸亚铁铵"不会被认成"硫酸"
 */
@Slf4j
@Component
public class TermDictionary {

    public static final String OBJECT = "检测对象";
    public static final String COMPONENT = "组分";
    public static final String METHOD = "方法";

    /**
     * @param canonical 规范写法
     * @param category  类别：检测对象 / 组分 / 方法 / 试剂 / 仪器 / 标准号
     */
    public record Term(String canonical, String category) {
    }

    private final Map<String, Term> byForm;
    private final int maxFormLength;

    @Autowired
    public TermDictionary(ResourceLoader resourceLoader,
                          @Value("${rag.knowledge.terms-location:classpath:kq/terms.csv}") String location) {
        this(load(resourceLoader.getResource(location)));
    }

    TermDictionary(List<String> csvLines) {
        Map<String, Term> forms = new HashMap<>();
        for (String line : csvLines) {
            String[] cells = line.split(",", -1);
            if (cells.length < 3 || cells[0].isBlank() || "term".equals(cells[0].trim())) {
                continue;
            }
            Term term = new Term(cells[0].trim(), cells[2].trim());
            List<String> variants = new ArrayList<>();
            variants.add(cells[0]);
            variants.addAll(List.of(cells[1].split("\\|")));
            for (String variant : variants) {
                String form = form(variant);
                if (!form.isEmpty()) {
                    forms.putIfAbsent(form, term);
                }
            }
        }
        this.byForm = Map.copyOf(forms);
        this.maxFormLength = forms.keySet().stream().mapToInt(String::length).max().orElse(0);
    }

    private static List<String> load(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("术语表不存在，文档元数据抽取与查询侧加权将不识别任何术语：{}", resource);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取术语表失败：" + resource, e);
        }
    }

    private static String form(String text) {
        return IngestionTextNormalizer.matchForm(text).toLowerCase(Locale.ROOT);
    }

    /**
     * 从左到右做最长匹配，返回命中的术语（按出现顺序，可能重复）
     */
    public List<Term> scan(String text) {
        String value = form(text);
        List<Term> hits = new ArrayList<>();
        int i = 0;
        while (i < value.length()) {
            int matched = 0;
            for (int length = Math.min(maxFormLength, value.length() - i); length > 0; length--) {
                Term term = byForm.get(value.substring(i, i + length));
                if (term != null) {
                    hits.add(term);
                    matched = length;
                    break;
                }
            }
            i += matched > 0 ? matched : 1;
        }
        return hits;
    }

    /**
     * 文本里提到的某一类别术语，规范写法、去重、按出现顺序
     */
    public List<String> canonicals(String text, String category) {
        Set<String> result = new LinkedHashSet<>();
        for (Term term : scan(text)) {
            if (term.category().equals(category)) {
                result.add(term.canonical());
            }
        }
        return List.copyOf(result);
    }

    public int size() {
        return byForm.size();
    }

    /**
     * 全部写法的比对形态（规范写法与同义写法），全文分词把含汉字的写法加进用户词典
     */
    public Set<String> forms() {
        return byForm.keySet();
    }

    /**
     * 按比对形态查术语；传入的已是分词结果时不再归一化
     */
    public Term termOf(String form) {
        return form == null ? null : byForm.get(form);
    }

    /**
     * 术语规范写法的比对形态，全文索引用它把同义写法归到同一个词项
     */
    public static String canonicalForm(Term term) {
        return form(term.canonical());
    }
}
