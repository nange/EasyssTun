package com.easysstun

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class UpdateCardViewTest {

    private val applicationContext: Context = ApplicationProvider.getApplicationContext()

    // Material 控件需要 App 主题，Robolectric 的 application context 不带。
    private val context: Context =
        ContextThemeWrapper(applicationContext, R.style.Theme)

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun manager(): UpdateManager = UpdateManager(
        UpdateEnvironment(
            context = applicationContext,
            pref = Pref(applicationContext),
            scope = CoroutineScope(Dispatchers.Unconfined),
            sourceFactory = { throw AssertionError("the test never checks for updates") },
            verifier = { _, _ -> InstallCheck.Ok },
            clock = { 0L },
            isDebugBuild = true,
            currentVersionCode = BuildConfig.VERSION_CODE,
            currentVersionName = BuildConfig.VERSION_NAME,
            supportedAbis = listOf("arm64-v8a"),
            tunnelReady = MutableStateFlow(false),
        )
    )

    private fun boundManagerOf(view: UpdateCardView): Any? {
        val field = UpdateCardView::class.java.getDeclaredField("boundManager")
        field.isAccessible = true
        return field.get(view)
    }

    @Test
    fun showsAppVersion() {
        val view = UpdateCardView(context)
        val version = view.findViewById<TextView>(R.id.update_current_version).text.toString()

        assertTrue(version.contains(BuildConfig.VERSION_NAME))
        assertTrue(version.contains(BuildConfig.VERSION_CODE.toString()))
    }

    @Test
    fun debugBuildExplainsWhyUpdatesAreDisabled() {
        val view = UpdateCardView(context)

        assertFalse(view.findViewById<View>(R.id.update_check_button).isEnabled)
        assertFalse(view.findViewById<View>(R.id.update_auto_check_switch).isEnabled)
        assertEquals(
            View.GONE,
            view.findViewById<View>(R.id.update_download_button).visibility,
        )
        assertEquals(
            context.getString(R.string.update_debug_disabled),
            view.findViewById<TextView>(R.id.update_status).text.toString(),
        )
    }

    @Test
    fun detachForgetsTheBindingSoReattachCanResubscribe() {
        val view = UpdateCardView(context)
        val manager = manager()
        val owner = TestOwner()
        view.bind(manager, Pref(applicationContext), owner)
        assertSame(manager, boundManagerOf(view))

        // detach 会取消状态订阅并忘记绑定，之后用同一 manager/owner 再 bind() 才不会被跳过。
        val onDetached = View::class.java.getDeclaredMethod("onDetachedFromWindow")
        onDetached.isAccessible = true
        onDetached.invoke(view)
        assertNull("detach must forget the bound manager", boundManagerOf(view))

        view.bind(manager, Pref(applicationContext), owner)
        assertSame(manager, boundManagerOf(view))
    }
}
