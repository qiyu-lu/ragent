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

package com.nageoffer.ai.ragent.rag.core.fulltext;

import com.nageoffer.ai.ragent.core.ingest.metadata.TermDictionary;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用仓库里的术语表与停用词表（生产配置）跑分词，索引与查询走同一个函数
 */
class FullTextTokenizerTest {

    private static final FullTextTokenizer TOKENIZER = new FullTextTokenizer(
            new TermDictionary(new DefaultResourceLoader(), "classpath:kq/terms.csv"),
            new DefaultResourceLoader(), "classpath:kq/stopwords.txt");

    private static List<String> terms(String text) {
        return TOKENIZER.queryTerms(text);
    }

    @Test
    void termDictionaryKeepsDomainTermsWhole() {
        List<String> terms = terms("钛铁矿精矿测全铁用什么熔剂熔样");
        assertTrue(terms.containsAll(List.of("钛铁矿精矿", "全铁", "熔剂")), terms.toString());
        assertFalse(terms.contains("测全"), "不加术语表时 jieba 会切出\"测全 / 铁用\"：" + terms);
        assertFalse(terms.contains("铁矿"), "钛铁矿精矿不能拆出铁矿：" + terms);
        assertTrue(terms("加入 8 g～10 g 焦硫酸钾").contains("焦硫酸钾"));
        assertTrue(terms("三氯化钛还原法测全铁").containsAll(List.of("三氯化钛还原法", "全铁")));
    }

    @Test
    void standardNumbersFormulasAndNumbersWithUnitsAreCutOutWhole() {
        assertTrue(terms("GB/T 6730.10—2014 铁矿石 硅含量的测定").containsAll(List.of("gb/t6730.10", "gb/t6730.10-2014")));
        assertTrue(terms("YS/T360.2").contains("ys/t360.2"));
        List<String> heating = terms("温度控制在 400 ℃±20 ℃，放置 1min");
        assertTrue(heating.containsAll(List.of("400°c", "20°c", "1min")), heating.toString());
        List<String> titrant = terms("重铬酸钾标准滴定溶液 c(1/6K2Cr2O7)=0.016 67 mol/L");
        assertTrue(titrant.containsAll(List.of("k2cr2o7", "0.01667mol/l", "重铬酸钾")), titrant.toString());
        assertTrue(terms("称取 0.500 0 g 试样，粒度小于 100 μm").containsAll(List.of("0.5000g", "100μm")));
        assertTrue(terms("硅含量 1.00%～10.00%").containsAll(List.of("1.00%", "10.00%")));
        assertTrue(terms("换算成 Fe2O3、FeO、Fe3O4").containsAll(List.of("fe2o3", "feo", "fe3o4")));
        List<String> iso = terms("ISO 3082 规定");
        assertTrue(iso.contains("iso3082"), iso.toString());
        assertFalse(iso.contains("iso"), "ISO 不是化学式：" + iso);
    }

    @Test
    void latexCommandNamesAreNotTerms() {
        List<String> terms = terms("密度 $\\rho _ { 20 } = 1.19 \\mathrm { g / m L }$，加热至 $\\frac { 1 } { 2 }$");
        assertFalse(terms.contains("mathrm") || terms.contains("frac") || terms.contains("rho"), terms.toString());
        assertTrue(terms.contains("密度"));
    }

    @Test
    void fullWidthTextIsNormalizedBeforeSegmentation() {
        assertEquals(terms("GB/T 6730.10"), terms("ＧＢ／Ｔ　６７３０．１０"));
    }

    @Test
    void synonymsAddTheCanonicalTermAtTheSamePositionAndKeepTheirOwnForm() {
        assertTrue(terms("TFe 含量").containsAll(List.of("tfe", "全铁")));
        assertTrue(terms("全铁量的测定").containsAll(List.of("全铁量", "全铁")));
        assertTrue(terms("以 SiO2 计").containsAll(List.of("sio2", "硅")));
        List<String> preparation = terms("制样阶段");
        List<String> sampling = terms("取样阶段");
        assertTrue(preparation.containsAll(List.of("制样", "取样制样")), preparation.toString());
        assertFalse(preparation.contains("取样"), "制样与取样仍分得开：" + preparation);
        assertTrue(sampling.contains("取样"));

        Map<String, Integer> positions = TOKENIZER.tokenize("全铁量").stream()
                .collect(Collectors.toMap(FullTextTokenizer.Token::term, FullTextTokenizer.Token::position));
        assertEquals(positions.get("全铁量"), positions.get("全铁"));
    }

    @Test
    void stopwordsPunctuationAndSingleAsciiCharactersAreDropped() {
        List<String> terms = terms("钛铁矿精矿全铁测定要称多少克？是什么方法 c (1)");
        for (String dropped : List.of("多少", "什么", "是", "？", "c", "1", "(")) {
            assertFalse(terms.contains(dropped), dropped + " 应被去掉：" + terms);
        }
    }

    @Test
    void wordsGuessedByTheHmmAlsoYieldTheirCharacters() {
        List<String> terms = terms("硫精矿测硅时试样要过多少微米的筛");
        assertTrue(terms.contains("硅"), "HMM 把\"测硅时\"粘成一个词时要补出单字：" + terms);
        assertTrue(terms.containsAll(List.of("硫精矿", "试样", "微米", "筛")), terms.toString());
    }

    @Test
    void queryTermsAreTheDistinctIndexTermsInOrder() {
        String text = "全铁量 全铁 TFe 全铁";
        List<String> expected = TOKENIZER.tokenize(text).stream().map(FullTextTokenizer.Token::term).distinct().toList();
        assertEquals(expected, terms(text));
        assertTrue(terms("").isEmpty());
        assertTrue(terms("？！，。").isEmpty());
    }
}
