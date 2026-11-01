package com.easysstun

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 守护 hev-socks5-tunnel AAR 的 JNI 契约：native 在 JNI_OnLoad 中按 `hev/htproxy/TProxyService` 类名与静态 `TProxyXxx` 签名注册，AAR 不匹配则运行时 UnsatisfiedLinkError；`Class.forName(initialize = false)` 避免触发静态块 System.loadLibrary（Robolectric 无 .so）。
 */
class TProxyJniContractTest {

    @Test
    fun aarBindingClassMatchesJniContract() {
        val clazz = Class.forName("hev.htproxy.TProxyService", false, javaClass.classLoader)
        val declared = clazz.declaredMethods.map {
            Triple(it.name, it.parameterTypes.toList(), it.modifiers)
        }
        val expected = mapOf(
            "TProxyStartService" to listOf(String::class.java, Int::class.javaPrimitiveType),
            "TProxyStopService" to emptyList<Class<*>>(),
            "TProxyIsRunning" to emptyList<Class<*>>(),
            "TProxyGetStats" to emptyList<Class<*>>(),
        )
        for ((name, params) in expected) {
            val found = declared.filter { it.first == name }
            val match = found.find { it.second == params }
            assertTrue(
                "Missing native method $name with params $params; AAR declares " +
                    "${found.map { it.first to it.second }}",
                match != null
            )
            assertTrue(
                "Native method $name must be static and native (RegisterNatives target)",
                Modifier.isStatic(match!!.third) && Modifier.isNative(match.third)
            )
        }
    }
}
