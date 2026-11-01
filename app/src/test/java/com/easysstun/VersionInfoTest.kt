package com.easysstun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖启动版本横幅 [versionBanner]：libeasyss 的 gomobile 绑定不会打印该信息，只能由 App 侧补上。
 */
class VersionInfoTest {

    @Test
    fun versionBanner_reportsAppAndLibeasyssVersion() {
        val banner = versionBanner()

        assertTrue(banner.startsWith("EasyssTun v${BuildConfig.VERSION_NAME} ("))
        assertTrue(banner.contains("| libeasyss "))
        // 标签后必须有版本号：单测里 native 库通常加载失败，此时退化为 "unknown"。
        assertFalse(banner.endsWith("libeasyss "))
    }

    @Test
    fun libeasyssVersion_neverThrowsWithoutNativeLibrary() {
        assertTrue(libeasyssVersion().isNotBlank())
    }

    @Test
    fun versionLogTag_isCapturedByLogStore() {
        // 只有 tag 在 LogStore 启动的 logcat 过滤列表里，横幅才看得到。
        assertTrue(LogTags.ALL.contains(VERSION_LOG_TAG))
    }
}
