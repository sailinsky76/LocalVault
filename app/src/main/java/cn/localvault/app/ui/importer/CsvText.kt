/*
 * Copyright (C) 2026 sailinsky76
 *
 * This file is part of LocalVault (本地保险库).
 *
 * LocalVault is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LocalVault is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LocalVault.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package cn.localvault.app.ui.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * CSV 导入的第一层：**一堆字节 → 一段文本**。
 *
 * **没有一行 `android.*`，也没有一行 Compose。** 解析在 [CsvParser]，列映射在 M5-2a-2，页面在 M5-2b。
 *
 * ───────────── 为什么这一层要单独存在 ─────────────
 *
 * 因为「乱码」是 CSV 导入这条路上**最常见、也最容易被误诊**的失败。
 * 用户看到的是「名称」变成「鍚嶇О」，他会得出的结论是「这个 App 不支持中文」，
 * 而真相是那份文件是 GBK 编码的——国内几个浏览器和一些管理器的导出至今如此。
 *
 * 更要命的是这种失败**不会报错**：`String(bytes)` 永远能给出一个字符串，
 * 只是里面全是垃圾。垃圾会一路走过解析、走过列映射，最后变成几百条乱码条目
 * 存进保险库——而那一步是**加密写盘**，事后再想收拾，成本比一开始就拒绝高得多。
 *
 * 所以这一层的规矩是：**宁可拒绝，也不猜。** 三条：
 *  1. 有 BOM 就认 BOM，一个字节都不含糊；
 *  2. 没有 BOM 时先按 UTF-8 **严格**解（`REPORT`，不是替换成 `?`），过了就是 UTF-8；
 *  3. 过不了才退 GBK，**同样严格**。两条都过不了，就明说这不是一份文本文件。
 *
 * 第 2 条的严格是关键：默认的解码器遇到坏字节会悄悄替换成 U+FFFD，
 * 于是「解成功了」这个信号就废了。GBK 的中文字节序列几乎不可能同时是合法 UTF-8
 * （高位字节的续字节范围对不上），反过来也一样，所以这两条路互不抢生意。
 *
 * ───────────── 关于这一层碰到的东西 ─────────────
 *
 * 这段文本里躺着用户全部的明文密码，而且**是 String，擦不掉**
 * （决策(143)，见下面 [PLAINTEXT_NOTE]）。所以这个文件里：
 * 一行日志都没有，任何返回值的 `toString()` 都不吐内容，出错也不把片段带进消息里。
 */
object CsvText {

    /**
     * 读进内存的上限。**和 `SafImportSource` 那个 64 MiB 不是一回事**：
     * 那边读进来的是密文，原样落盘就完了；这边读进来的是明文，
     * 还要解码成 String（UTF-8 中文最坏情况 ×2 内存）、切成几万个小 String、
     * 再在预览页上摆一遍。16 MiB 的 CSV 大约是三十万条密码，
     * 已经比任何真实的密码库大两个数量级。
     */
    const val MAX_BYTES: Long = 16L * 1024 * 1024

    /** 识别出来的编码。给用户看的那一行「编码：GBK」就是它。 */
    enum class Encoding(val label: String) {
        Utf8Bom("UTF-8（带 BOM）"),
        Utf8("UTF-8"),
        Utf16Le("UTF-16 LE"),
        Utf16Be("UTF-16 BE"),
        Gbk("GBK / GB18030"),
    }

    sealed interface Decoded {
        /**
         * 解出来了。[text] 里**已经不含 BOM**，也不含 NUL。
         *
         * `toString` 只报形状不报内容——这个对象里是几百个明文密码，
         * 哪天有人顺手把它塞进一句日志或者一个异常消息里，就等于把整份 CSV
         * 抄进了 logcat（决策(144)）。
         */
        class Ok(val text: String, val encoding: Encoding) : Decoded {
            override fun toString(): String = "CsvText.Ok(${encoding.name}, ${text.length} chars)"
        }

        /** 文件太大。这一条**在解码之前**就判掉，不能先读进来再说。 */
        data class TooBig(val sizeBytes: Long) : Decoded

        /** 空文件。单独一类：它和「不是文本」给出的下一步不一样。 */
        data object Empty : Decoded

        /** 两种编码都严格解不出来，或者解出来一看就是二进制。最常见的原因：选错文件了。 */
        data object NotText : Decoded

        /** 认得出是 UTF-32，但我们不支持。单独报，免得它掉进「选错文件」里。 */
        data object Utf32 : Decoded
    }

    /**
     * 把字节解成文本。**不看文件名，也不看扩展名**——同决策㉒，
     * 理由和恢复页那边一模一样：系统文件选择器会改扩展名，用户自己也会改。
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size.toLong() > MAX_BYTES) return Decoded.TooBig(bytes.size.toLong())
        if (bytes.isEmpty()) return Decoded.Empty

        // ── BOM。顺序有讲究：UTF-32LE 的 BOM 头两个字节和 UTF-16LE 一模一样
        //    （FF FE 00 00 vs FF FE），先判长的那个，否则 UTF-32 文件会被
        //    当成 UTF-16 解出满屏 NUL，然后掉进「不是文本」，报出一句不准的话。
        if (startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF) ||
            startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00)
        ) {
            return Decoded.Utf32
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return finish(strict(bytes, 3, StandardCharsets.UTF_8), Encoding.Utf8Bom)
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return finish(strict(bytes, 2, charsetOrNull("UTF-16LE")), Encoding.Utf16Le)
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return finish(strict(bytes, 2, charsetOrNull("UTF-16BE")), Encoding.Utf16Be)
        }

        // ── 没有 BOM。先拦二进制：一个 NUL 字节就够了。
        //    正经的 CSV 里不会有 NUL，而图片 / 压缩包 / 数据库文件里遍地都是。
        //    这一刀在解码之前砍，比解码之后看结果便宜得多。
        if (bytes.any { it == 0.toByte() }) return Decoded.NotText

        strict(bytes, 0, StandardCharsets.UTF_8)?.let { return finish(it, Encoding.Utf8) }
        strict(bytes, 0, charsetOrNull("GBK"))?.let { return finish(it, Encoding.Gbk) }
        return Decoded.NotText
    }

    /* ───────────────────── 内部 ───────────────────── */

    private fun startsWith(b: ByteArray, vararg prefix: Int): Boolean {
        if (b.size < prefix.size) return false
        for (i in prefix.indices) if (b[i] != prefix[i].toByte()) return false
        return true
    }

    /** GBK 在安卓上一直都在，但 `forName` 的签名会抛，不值得为它让调用方写 try。 */
    private fun charsetOrNull(name: String): Charset? =
        try {
            Charset.forName(name)
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * 严格解码：坏字节 **报错**，不替换。
     * 用默认的 `String(bytes, cs)` 会把坏字节变成 U+FFFD 然后返回成功，
     * 那样这个函数的返回值就不再携带任何信息了。
     */
    private fun strict(bytes: ByteArray, offset: Int, cs: Charset?): String? {
        if (cs == null) return null
        val decoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) {
            null
        } catch (e: OutOfMemoryError) {
            // 16 MiB 的上限之下正常到不了这里，但解码是这一层唯一会成倍吃内存的动作，
            // 真撞上时给一句「不是文本文件」也比整个进程被干掉强。
            null
        }
    }

    private fun finish(text: String?, enc: Encoding): Decoded {
        if (text == null) return Decoded.NotText
        // 有些导出会在 BOM 之后又写一个 U+FEFF，也有 UTF-16 解完自带一个。
        // 留着它，第一列的列名就会以一个看不见的字符开头，然后
        // 「name」这一列在列映射里怎么都对不上——一个极难自查的失败。
        val t = text.removePrefix("\uFEFF")
        if (t.isEmpty()) return Decoded.Empty
        if (!looksTextual(t)) return Decoded.NotText
        return Decoded.Ok(t, enc)
    }

    /**
     * 解出来了不等于是文本。GBK 覆盖的字节范围很宽，一份二进制文件偶尔能被它
     * 「成功」解成一串控制字符——虽然实测两百份随机字节没有一份过得了严格 GBK，
     * 但这道网便宜，留着。
     *
     * 只看前 4096 个字符：真是二进制的话，垃圾在开头就已经遍地都是。
     */
    private fun looksTextual(t: String): Boolean {
        val n = minOf(t.length, 4096)
        for (i in 0 until n) {
            val c = t[i]
            if (c == '\t' || c == '\n' || c == '\r') continue
            if (c.code < 0x20 || c.code == 0x7F) return false
        }
        return true
    }

    /* ───────────────────── 文案 ───────────────────── */

    /**
     * 失败时说什么。四条各自指向**不同的下一步**，这正是它们不合并成一句
     * 「文件无法读取」的原因（同 `RestoreModel` 那八条的用意）。
     *
     * 每一条都不写「稍后重试」——这四种失败重试一百次结果都一样。
     */
    fun message(d: Decoded): String = when (d) {
        is Decoded.Ok -> ""
        is Decoded.TooBig ->
            "这个文件有 ${d.sizeBytes / 1024 / 1024} MB，超过了导入的上限（${MAX_BYTES / 1024 / 1024} MB）。" +
                "密码导出文件通常只有几十 KB——这么大多半是选错了文件，请确认选的是那份密码导出的 CSV。"
        Decoded.Empty ->
            "这个文件是空的，一个字节都没有。多半是导出那一步没成功，回原来那个应用重新导一次。"
        Decoded.NotText ->
            "这不是一份文本文件（打开看到的是二进制内容），CSV 应该是纯文本。" +
                "最常见的原因是在文件选择器里点错了——请找那份以 .csv 结尾的导出文件。"
        Decoded.Utf32 ->
            "这个文件是 UTF-32 编码的，这里只认 UTF-8、UTF-16 和 GBK。" +
                "用记事本或表格软件打开它，另存为 UTF-8 的 CSV 再来一次。"
    }

    /**
     * 决策(143)：**这段明文擦不掉，别假装擦得掉。**
     *
     * 全工程的敏感数据一直走 `SecureBytes`（`ByteArray`，用完清零）。
     * 到了这一层做不到：`CharsetDecoder` 的产物是 `String`，String 在 JVM 上不可变，
     * 拿不到它的底层数组，也没有任何合法办法把它清零；就算硬来，
     * 解析过程中还会分裂出几万个小 String，它们全都散落在堆上等 GC。
     *
     * 所以这里不写一个叫 `wipe()` 的空方法来让自己心安。能做的只有三件，
     * 三件都做了：**活得短**（导入一结束就丢引用）、**不外泄**
     * （不打日志、`toString` 不吐内容、异常消息里不带片段）、
     * **说实话**（M5-2b 那一页导入完必须强提示删掉源文件——
     * 那份 CSV 躺在「下载」目录里，本身就是一份任何应用都读得到的明文密码表，
     * 它的危险远大于我们内存里这几百毫秒）。
     */
    const val PLAINTEXT_NOTE: String =
        "CSV 是明文的：这份文件在被导入之前，任何能读你手机存储的应用都能看到里面的全部密码。" +
            "导入完成后请立刻删掉它——这不是客套话，它比你的保险库好打开得多。"
}
