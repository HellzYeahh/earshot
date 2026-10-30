package dev.earshot.ui.onboarding

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The notification permission is asked once per install, not on every cold start (audit R44). */
class NotificationPromptStoreTest {

    @Test
    fun `the first start asks, every later start does not`() {
        val prefs = MemoryPrefs()
        assertTrue(NotificationPromptStore { prefs }.claimFirstAsk())
        // A new store is what a cold start builds: the answer must come from storage.
        assertFalse(NotificationPromptStore { prefs }.claimFirstAsk())
        assertFalse(NotificationPromptStore { prefs }.claimFirstAsk())
    }

    @Test
    fun `a released ask is asked again on the next start, once`() {
        val prefs = MemoryPrefs()
        assertTrue(NotificationPromptStore { prefs }.claimFirstAsk())
        // Android cancelled that request (another one was open): it gives the ask back.
        NotificationPromptStore { prefs }.release()
        assertTrue(NotificationPromptStore { prefs }.claimFirstAsk())
        assertFalse(NotificationPromptStore { prefs }.claimFirstAsk())
    }

    @Test
    fun `releasing an ask that was never claimed changes nothing`() {
        val prefs = MemoryPrefs()
        NotificationPromptStore { prefs }.release()
        assertTrue(NotificationPromptStore { prefs }.claimFirstAsk())
        assertFalse(NotificationPromptStore { prefs }.claimFirstAsk())
    }

    @Test
    fun `a fresh install asks again`() {
        assertTrue(NotificationPromptStore { MemoryPrefs() }.claimFirstAsk())
        assertTrue(NotificationPromptStore { MemoryPrefs() }.claimFirstAsk())
    }
}

/** In-memory SharedPreferences; only what the store uses needs to be faithful. */
private class MemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String?, Any?>()

    override fun getAll(): MutableMap<String, *> = values.filterKeys { it != null }.mapKeys { it.key!! }.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String?): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String?, Any?>()
        private var clearAll = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            pending.clear()
        }
    }
}
