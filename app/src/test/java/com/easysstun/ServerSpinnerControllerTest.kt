package com.easysstun

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.widget.Spinner
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 覆盖首页服务器下拉框：控制器是唯一能保证「回填 active 项不被误当成用户切换」以及「刷新不重建未变化的列表」的地方。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ServerSpinnerControllerTest {

    private lateinit var context: Context
    private lateinit var store: ProfileStore
    private lateinit var spinner: Spinner
    private var selected: Profile? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfileStore.resetForTests()
        store = ProfileStore.get(context)
        spinner = Spinner(ContextThemeWrapper(context, R.style.Theme))
        selected = null
    }

    @After
    fun tearDown() {
        ProfileStore.resetForTests()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun profile(id: String, name: String) = Profile(
        id = id,
        name = name,
        server = "$id.example.com",
        serverPort = 443,
        password = "secret",
    )

    private fun controller() =
        ServerSpinnerController(context, spinner, store) { selected = it }

    /**
     * 按 spinner 在下次布局时的行为触发监听：此时 [ServerSpinnerController.refresh] 已返回。
     */
    private fun select(position: Int) {
        requireNotNull(spinner.onItemSelectedListener)
            .onItemSelected(spinner, null, position, position.toLong())
    }

    @Test
    fun refresh_bindsTheNamesAndRestoresTheActiveEntry() {
        store.add(profile("a", "Alpha"))
        store.add(profile("b", "Bravo"))
        store.select("b")

        controller().refresh()

        assertEquals(2, spinner.adapter.count)
        assertEquals(1, spinner.selectedItemPosition)
        assertEquals("Bravo", spinner.selectedItem)
        assertNull("restoring a selection is not a user action", selected)
    }

    @Test
    fun restoringTheActiveEntry_isNotDispatchedAfterRefreshReturns() {
        store.add(profile("a", "Alpha"))
        store.add(profile("b", "Bravo"))
        store.select("b")
        controller().refresh()

        // spinner 在下次布局时才派发该回调，即 refresh() 之后。
        select(1)

        assertNull(selected)
    }

    @Test
    fun userSelectingAnotherEntry_isDispatched() {
        store.add(profile("a", "Alpha"))
        store.add(profile("b", "Bravo"))
        store.select("b")
        controller().refresh()

        select(0)

        assertEquals("a", selected?.id)
    }

    @Test
    fun refresh_reusesTheAdapterWhenTheProfileListIsUnchanged() {
        store.add(profile("a", "Alpha"))
        store.select("a")
        val controller = controller()
        controller.refresh()
        val adapter = spinner.adapter

        controller.refresh()

        assertSame("an unchanged list must not rebuild the adapter", adapter, spinner.adapter)
    }

    @Test
    fun refresh_rebuildsTheAdapterAndSelectionWhenTheListChanges() {
        store.add(profile("a", "Alpha"))
        store.select("a")
        val controller = controller()
        controller.refresh()
        val adapter = spinner.adapter

        store.add(profile("b", "Bravo"))
        store.select("b")
        controller.refresh()

        assertNotSame(adapter, spinner.adapter)
        assertEquals(2, spinner.adapter.count)
        assertEquals(1, spinner.selectedItemPosition)
        assertEquals("Bravo", spinner.selectedItem)
    }
}
