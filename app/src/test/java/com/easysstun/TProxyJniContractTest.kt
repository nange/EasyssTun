package com.easysstun

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Guards the JNI contract of the prebuilt hev-socks5-tunnel AAR.
 *
 * The AAR ships its Java binding class, and the natives are registered to that
 * exact class at load time
 * (JNI_OnLoad -> FindClass("hev/htproxy/TProxyService") + RegisterNatives).
 * Upgrading the AAR without matching that contract makes System.loadLibrary
 * fail with UnsatisfiedLinkError at runtime, so the class, the native method
 * names/signatures, and their staticness are pinned here. A missing binding
 * class fails the build earlier, at Kotlin compile time.
 *
 * The class is inspected with Class.forName(initialize = false) so the binding
 * class's static initializer (System.loadLibrary) is NOT triggered under
 * Robolectric, where no Android .so is available.
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
