package com.easysstun

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 服务器配置档仓库。active id 永不悬空：select 忽略未知 id，删除当前项回退到首个幸存项，删空才清指针；add 不改选中。
 * changes 在所有相关键写完后才自增，内联恢复的收集者总能读到变更后的快照；隧道是否重载由调用方经 [ConfigChangeNotifier] 决定。
 * [LEGACY_SERVER_PROFILES] 是旧版写入的冻结快照（降级后按它读取，本版删掉的配置档会复现）；删空时清除，避免残留明文密码。 */
internal class ProfileStore private constructor(private val pref: Pref) {

    /** 每次变更后自增，观察者据此重载列表。 */
    private val revision = MutableStateFlow(0L)

    /** 变更事件：新收集者会立刻收到当前 revision，因此变更之后才开始观察的界面仍会刷新一次。 */
    val changes: StateFlow<Long> = revision.asStateFlow()

    /** 已解码失败的原始 payload：同一份内容只打一次日志，避免日志界面频繁读当前配置档时刷屏。 */
    @Volatile
    private var corruptPayload: String? = null

    /** 全部配置档，按插入顺序。payload 损坏时读作空列表并只记一次日志，避免每个列配置档的界面崩溃；用户仍可从表单新建。 */
    fun all(): List<Profile> {
        val json = pref.getString(SERVER_PROFILES) ?: return emptyList()
        return try {
            appJson.decodeFromString<List<Profile>>(json).also { corruptPayload = null }
        } catch (e: Exception) {
            logCorruptOnce(json, e)
            emptyList()
        }
    }

    fun find(id: String): Profile? = all().firstOrNull { it.id == id }

    /** 隧道实际连接的配置档；未选中时为 null。 */
    fun active(): Profile? = activeId()?.let(::find)

    fun activeId(): String? = pref.getString(ACTIVE_SERVER_ID)

    fun add(profile: Profile) {
        save(all() + profile)
    }

    fun update(profile: Profile) {
        val profiles = all().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index == -1) {
            Log.w(TAG, "update(${profile.id}): no such profile, ignoring")
            return
        }
        profiles[index] = profile
        save(profiles)
    }

    /** 删除 [id]：删的是当前项时把指针移到首个幸存项，只有删空才清空指针，避免出现有配置档却无选中。 */
    fun delete(id: String) {
        val profiles = all()
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.size == profiles.size) {
            Log.w(TAG, "delete($id): no such profile, ignoring")
            return
        }
        // 先移指针再发事件：内联恢复的收集者必须看到删除后的快照，而不是刚消失的 id。
        if (activeId() == id) {
            pref.putString(ACTIVE_SERVER_ID, remaining.firstOrNull()?.id)
        }
        save(remaining)
    }

    /** 把指针指向 [id]；未知 id 忽略，指针因此永不悬空。 */
    fun select(id: String) {
        if (find(id) == null) {
            Log.w(TAG, "select($id): no such profile, ignoring")
            return
        }
        pref.putString(ACTIVE_SERVER_ID, id)
        notifyChanged()
    }

    /** 旧 key 的一次性迁移，在实例创建时执行而非首次读取：[all] 才能保持纯读，也避免读-改-写期间覆盖其他线程的写入。 */
    private fun migrateLegacyStore() {
        if (pref.getString(SERVER_PROFILES) != null) return
        val legacy = pref.getString(LEGACY_SERVER_PROFILES) ?: return
        Log.i(TAG, "migrating the legacy profile store into $SERVER_PROFILES")
        pref.putString(SERVER_PROFILES, legacy)
    }

    private fun logCorruptOnce(json: String, error: Exception) {
        if (corruptPayload == json) return
        corruptPayload = json
        Log.e(TAG, "stored profiles are unreadable, treating them as empty: ${error.message}", error)
    }

    private fun save(profiles: List<Profile>) {
        pref.putString(SERVER_PROFILES, appJson.encodeToString(profiles))
        if (profiles.isEmpty()) {
            // 没有配置档时降级兼容无意义，而冻结快照仍含明文密码：删空即清除。
            pref.remove(LEGACY_SERVER_PROFILES)
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        revision.value = revision.value + 1
    }

    companion object {
        private const val TAG = LogTags.PROFILE_STORE

        /** 当前存储 key：JSON 里数字与布尔是原生类型。 */
        const val SERVER_PROFILES = "server_profiles_v2"

        /** 类型化模型之前的旧 key：只读一次用于迁移，之后保留给降级版本读取。 */
        const val LEGACY_SERVER_PROFILES = "server_profiles"

        /** key 未变：新旧两种格式下指针都是纯配置档 id。 */
        const val ACTIVE_SERVER_ID = "active_server_id"

        @Volatile
        private var instance: ProfileStore? = null

        /** 进程内唯一实例：一次变更必须对所有界面可见。 */
        fun get(context: Context): ProfileStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val created = ProfileStore(Pref(context.applicationContext))
                    // 先迁移再发布实例：观察者不能看到半迁移的仓库。
                    created.migrateLegacyStore()
                    instance = created
                    created
                }
            }
        }

        internal fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
