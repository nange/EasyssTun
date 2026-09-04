package com.easysstun

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fixed JNI contract of the prebuilt hev-socks5-tunnel AAR.
 *
 * The AAR registers its natives to hev.htproxy.TProxyService at load time
 * (JNI_OnLoad -> FindClass + RegisterNatives). If the shim's package, class
 * name, or the native method names/signatures drift, System.loadLibrary
 * fails with UnsatisfiedLinkError at runtime.
 *
 * The class is inspected with Class.forName(initialize = false) so the
 * shim's init block (System.loadLibrary) is NOT triggered under Robolectric.
 */
class TProxyJniContractTest {

    @Test
    fun shimMatchesJniContract() {
        val clazz = Class.forName("hev.htproxy.TProxyService", false, javaClass.classLoader)
        val methods = clazz.declaredMethods
            .map { it.name to it.parameterTypes.toList() }
            .toList()
        val expected = mapOf(
            "TProxyStartService" to listOf(String::class.java, Int::class.javaPrimitiveType),
            "TProxyStopService" to emptyList<Class<*>>(),
            "TProxyIsRunning" to emptyList<Class<*>>(),
            "TProxyGetStats" to emptyList<Class<*>>(),
        )
        for ((name, params) in expected) {
            assertTrue("Missing native method $name", methods.any { it.first == name })
            assertTrue(
                "Native method $name signature mismatch: expected $params, " +
                    "found ${methods.filter { it.first == name }.map { it.second }}",
                methods.any { it.first == name && it.second == params }
            )
        }
    }
}
