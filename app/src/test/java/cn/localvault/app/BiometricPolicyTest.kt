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

package cn.localvault.app

import cn.localvault.app.ui.unlock.BiometricFailure
import cn.localvault.app.ui.unlock.BiometricPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生物识别失败该怎么处置。
 *
 * 这套判断在真机上极难覆盖：要复现「指纹库变更导致密钥失效」得去系统设置里
 * 删一个指纹再回来，要复现「锁死」得连续按错五次还得等硬件超时。
 * 把错误码到语义的映射切出去之后，剩下的策略部分就能在这里全部走一遍。
 */
class BiometricPolicyTest {

    @Test
    fun `用户自己取消不算错误，不该弹任何提示`() {
        assertFalse(BiometricPolicy.shouldShowMessage(BiometricFailure.UserCanceled))
        // 取消是一个正常出口：他只是想改用主密码，不该被红字吓一跳
        assertTrue(BiometricPolicy.biometricStillUsable(BiometricFailure.UserCanceled))
        assertFalse(BiometricPolicy.shouldForgetEnrollment(BiometricFailure.UserCanceled))
    }

    @Test
    fun `除取消之外都要给用户一句话`() {
        BiometricFailure.entries
            .filter { it != BiometricFailure.UserCanceled }
            .forEach {
                assertTrue("$it 应该显示提示", BiometricPolicy.shouldShowMessage(it))
                assertTrue("$it 的文案不能为空", BiometricPolicy.message(it).isNotBlank())
            }
    }

    @Test
    fun `临时锁定之后指纹还能用，不删绑定`() {
        val f = BiometricFailure.TemporaryLockout
        assertTrue("等一会儿还能按", BiometricPolicy.biometricStillUsable(f))
        assertFalse(BiometricPolicy.shouldForgetEnrollment(f))
    }

    @Test
    fun `永久锁定不删绑定`() {
        val f = BiometricFailure.PermanentLockout
        // 这一屏上不再摆指纹按钮
        assertFalse(BiometricPolicy.biometricStillUsable(f))
        // 但绝不能替用户把绑定删掉：用锁屏凭据解锁一次它就恢复了，
        // 删掉等于替他做了一个他没同意的决定，还得回设置页重新绑一遍
        assertFalse("锁死只是暂时进不去，不该删绑定", BiometricPolicy.shouldForgetEnrollment(f))
    }

    @Test
    fun `只有指纹库变更才需要忘掉绑定`() {
        val needForget = BiometricFailure.entries.filter { BiometricPolicy.shouldForgetEnrollment(it) }
        assertTrue("只该有 KeyInvalidated 一个", needForget == listOf(BiometricFailure.KeyInvalidated))
        assertFalse(BiometricPolicy.biometricStillUsable(BiometricFailure.KeyInvalidated))
    }

    @Test
    fun `指纹库变更的文案必须说明数据没丢`() {
        val msg = BiometricPolicy.message(BiometricFailure.KeyInvalidated)
        // 这是最容易被误解成「我的东西没了」的一种失败：
        // 用户什么都没干，指纹解锁突然不认了。文案里必须有交代。
        assertTrue("要说清不是故障", msg.contains("不是故障"))
        assertTrue("要说清数据没动", msg.contains("没动"))
        assertTrue("要给出下一步", msg.contains("主密码"))
    }

    @Test
    fun `每一条文案都要给出下一步该做什么`() {
        BiometricFailure.entries
            .filter { it != BiometricFailure.UserCanceled }
            .forEach {
                val msg = BiometricPolicy.message(it)
                assertTrue(
                    "$it 的文案里要有出路（主密码 / 重试 / 去系统设置）",
                    msg.contains("主密码") || msg.contains("再试") || msg.contains("设置"),
                )
            }
    }

    @Test
    fun `硬件不可用和没录指纹都要退回主密码`() {
        listOf(BiometricFailure.HardwareUnavailable, BiometricFailure.NoneEnrolled).forEach {
            assertFalse("$it 之后不该继续摆指纹按钮", BiometricPolicy.biometricStillUsable(it))
            assertTrue(BiometricPolicy.message(it).contains("主密码"))
        }
    }

    /**
     * 「传感器正忙」和「没有传感器」必须是两种失败。
     *
     * 上一版把 `ERROR_HW_UNAVAILABLE` 和 `ERROR_HW_NOT_PRESENT` 归成了一种，
     * 后果是真实的：自动锁定后切回应用，指纹框在系统还没把传感器交接完时被拉起，
     * 拿到「腾不出手」，界面却按「这台设备用不了指纹」处置——弹红字、撤按钮、
     * 把用户逼去输长主密码。而他切出去再切回来就好了。
     *
     * 所以这一条钉死两件事：这一种**不撤按钮**，而且文案里**不许**出现
     * 「请用主密码解锁」那种把人往回赶的说法。
     */
    @Test
    fun `传感器正忙是可以重试的不许撤掉指纹入口`() {
        assertTrue(
            "HardwareBusy 之后指纹这条路还在",
            BiometricPolicy.biometricStillUsable(BiometricFailure.HardwareBusy),
        )
        val msg = BiometricPolicy.message(BiometricFailure.HardwareBusy)
        assertTrue("要告诉用户可以再按一次", msg.contains("再按"))
        assertFalse(
            "不该把用户直接赶去输主密码——指纹这条路还能走",
            msg.contains("请用主密码解锁"),
        )
    }

    /** 反过来：真的没有传感器时，必须撤掉入口。两种失败不能互相污染。 */
    @Test
    fun `没有传感器时必须撤掉指纹入口`() {
        assertFalse(
            BiometricPolicy.biometricStillUsable(BiometricFailure.HardwareUnavailable),
        )
        assertFalse(
            "既然要撤掉，就不该说「再按一下」",
            BiometricPolicy.message(BiometricFailure.HardwareUnavailable).contains("再按"),
        )
    }
}
