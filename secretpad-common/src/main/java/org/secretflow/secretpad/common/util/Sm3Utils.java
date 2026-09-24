/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.secretflow.secretpad.common.util;

import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 国密 SM3 密码杂凑算法工具类 (GM/T 0004-2012 / GB/T 32918)
 *
 * @author cml
 * @date 2024/08/31
 */
public class Sm3Utils {

    /**
     * 计算字符串的 SM3 哈希（十六进制字符串，64位小写）
     *
     * @param content 待哈希字符串
     * @return 64位十六进制哈希字符串
     */
    public static String hash(String content) {
        if (content == null) {
            return "";
        }
        byte[] hash = hash(content.getBytes(StandardCharsets.UTF_8));
        return Hex.toHexString(hash);
    }

    /**
     * 计算字节数组的 SM3 哈希（32字节）
     *
     * @param content 待哈希字节数组
     * @return 32字节哈希结果
     */
    public static byte[] hash(byte[] content) {
        if (content == null) {
            return new byte[0];
        }
        SM3Digest digest = new SM3Digest();
        digest.update(content, 0, content.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);
        return hash;
    }

    /**
     * 计算文件的 SM3 哈希（十六进制字符串，64位小写）
     *
     * @param filePath 文件绝对或相对路径
     * @return 64位十六进制哈希字符串
     */
    public static String fileHash(String filePath) {
        try (InputStream in = new FileInputStream(filePath)) {
            SM3Digest digest = new SM3Digest();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hash = new byte[digest.getDigestSize()];
            digest.doFinal(hash, 0);
            return Hex.toHexString(hash);
        } catch (IOException e) {
            throw new RuntimeException("Calculate SM3 file hash error for: " + filePath, e);
        }
    }

    /**
     * 计算 HMAC-SM3（字节数组）
     *
     * @param key  密钥字节数组
     * @param data 数据字节数组
     * @return HMAC-SM3 摘要字节数组
     */
    public static byte[] hmacSm3(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SM3Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);
        byte[] result = new byte[hmac.getMacSize()];
        hmac.doFinal(result, 0);
        return result;
    }

    /**
     * 计算 HMAC-SM3（十六进制字符串）
     *
     * @param key  密钥字符串
     * @param data 数据字符串
     * @return HMAC-SM3 十六进制字符串
     */
    public static String hmacSm3(String key, String data) {
        byte[] keyBytes = (key != null ? key : "").getBytes(StandardCharsets.UTF_8);
        byte[] dataBytes = (data != null ? data : "").getBytes(StandardCharsets.UTF_8);
        byte[] result = hmacSm3(keyBytes, dataBytes);
        return Hex.toHexString(result);
    }
}
