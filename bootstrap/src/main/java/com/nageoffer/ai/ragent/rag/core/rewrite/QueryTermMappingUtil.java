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

package com.nageoffer.ai.ragent.rag.core.rewrite;

public class QueryTermMappingUtil {

    /**
     * 安全归一化替换：
     * - 只替换 sourceTerm
     * - 如果当前位置本身已经是 targetTerm 起始（例如文本中已经是“平安保司”），则不重复替换
     */
    //在 text 中查找 sourceTerm，如果找到了，就替换成 targetTerm；但如果当前位置已经是 targetTerm，就不重复替换。
    public static String applyMapping(String text, String sourceTerm, String targetTerm) {
        //如果原文本为空，或者源词为空，就没法替换，直接返回原文本。
        if (text == null || text.isEmpty() || sourceTerm == null || sourceTerm.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();//保存替换后的结果
        int idx = 0; //当前扫描到的位置
        int len = text.length(); //原文本长度
        int sourceLen = sourceTerm.length(); //源词长度
        int targetLen = targetTerm.length(); //目标词长度

        while (idx < len) {
            //从 idx 位置开始，查找下一次出现 sourceTerm 的位置。
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                // 后面没有命中，整体拷贝
                sb.append(text, idx, len);
                break;
            }

            // 先把命中之前的文本拷贝过去
            sb.append(text, idx, hit);

            // 判断当前位置是否已经是 targetTerm 的开头
            boolean alreadyTarget =
                    targetTerm != null
                            && hit + targetLen <= len
                            && text.startsWith(targetTerm, hit);

            if (alreadyTarget) {
                // 已经是目标词开头了，直接按原文拷贝 targetTerm，一次性跳过
                sb.append(text, hit, hit + targetLen);
                idx = hit + targetLen;
            } else {
                // 不是目标词开头，正常做归一化替换
                sb.append(targetTerm);
                idx = hit + sourceLen;
            }
        }

        return sb.toString();
    }
}
