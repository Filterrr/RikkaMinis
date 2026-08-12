package com.openminis.app.agent

import android.content.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SoulStoreTest {

    @Test
    fun `fileLocation returns correct path`() {
        val context = MockContext()
        val file = SoulStore.fileLocation(context)
        assertTrue(file.absolutePath.endsWith("minis-global/memory/SOUL.md"))
    }

    @Test
    fun `isOverLimit returns Ok for empty string`() {
        assertEquals(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit(""))
        assertEquals(SoulBodyLimitCheck.Ok, SoulStore.isOverLimit("   "))
    }

    @Test
    fun `isOverLimit returns Ok for short English text`() {
        val body = "Hello world"
        assertTrue(SoulStore.isOverLimit(body) is SoulBodyLimitCheck.Ok)
    }

    @Test
    fun `isOverLimit returns OverLimitEnglish for long English text`() {
        val body = "word ".repeat(2000).trim()
        val result = SoulStore.isOverLimit(body)
        assertTrue(result is SoulBodyLimitCheck.OverLimitEnglish)
        result as SoulBodyLimitCheck.OverLimitEnglish
        assertEquals(2000, result.words)
        assertEquals(1000, result.cap)
    }

    @Test
    fun `isOverLimit returns OverLimitChinese for long Chinese text`() {
        val body = "中".repeat(2000)
        val result = SoulStore.isOverLimit(body)
        assertTrue(result is SoulBodyLimitCheck.OverLimitChinese)
        result as SoulBodyLimitCheck.OverLimitChinese
        assertEquals(2000, result.chars)
        assertEquals(1600, result.cap)
    }

    @Test
    fun `isOverLimit returns Ok for short Chinese text`() {
        val body = "你好世界"
        assertTrue(SoulStore.isOverLimit(body) is SoulBodyLimitCheck.Ok)
    }

    @Test
    fun `isOverLimit considers CJK ratio threshold`() {
        val mixed = "a".repeat(100) + "中".repeat(30)
        val result = SoulStore.isOverLimit(mixed)
        assertTrue(result is SoulBodyLimitCheck.Ok)
    }

    @Test
    fun `DEFAULT_CONTENT has correct format`() {
        assertTrue(SoulStore.DEFAULT_CONTENT.startsWith("---"))
        assertTrue(SoulStore.DEFAULT_CONTENT.contains("name: \"RikkaMinis\""))
        assertTrue(SoulStore.DEFAULT_CONTENT.contains("lang: \"auto\""))
    }

    @Test
    fun `ensureExists creates file when not exists`() {
        val context = MockContext()
        val file = SoulStore.fileLocation(context)
        file.delete()
        assertFalse(file.exists())
        SoulStore.ensureExists(context)
        assertTrue(file.exists())
        assertEquals(SoulStore.DEFAULT_CONTENT, file.readText())
    }

    @Test
    fun `ensureExists does nothing when file exists`() {
        val context = MockContext()
        val file = SoulStore.fileLocation(context)
        file.parentFile?.mkdirs()
        file.writeText("existing content")
        SoulStore.ensureExists(context)
        assertEquals("existing content", file.readText())
    }

    @Test
    fun `load returns null when file not exists`() {
        val context = MockContext()
        SoulStore.fileLocation(context).delete()
        assertNull(SoulStore.load(context))
    }

    @Test
    fun `load parses valid file`() {
        val context = MockContext()
        SoulStore.ensureExists(context)
        val result = SoulStore.load(context)
        assertNotNull(result)
        assertEquals("RikkaMinis", result?.metadata?.name)
        assertTrue(result?.body?.isNotEmpty() == true)
    }

    @Test
    fun `load returns null on corrupted file`() {
        val context = MockContext()
        val file = SoulStore.fileLocation(context)
        file.parentFile?.mkdirs()
        file.writeText("not valid frontmatter")
        val result = SoulStore.load(context)
        assertNull(result)
    }

    @Test
    fun `save writes file and updates cache`() {
        val context = MockContext()
        val metadata = SoulMetadata("Test", "😀", "friendly", "en")
        val soulFile = SoulFile(metadata, "Test body")
        SoulStore.save(context, soulFile)
        val file = SoulStore.fileLocation(context)
        assertTrue(file.exists())
        val loaded = SoulStore.load(context)
        assertNotNull(loaded)
        assertEquals("Test", loaded?.metadata?.name)
        assertEquals("Test body", loaded?.body)
        assertEquals(metadata, SoulStore.cachedMetadata.value)
    }

    @Test
    fun `save creates parent directories`() {
        val context = MockContext()
        val file = SoulStore.fileLocation(context)
        file.parentFile?.deleteRecursively()
        assertFalse(file.parentFile?.exists() == true)
        val soulFile = SoulFile(SoulMetadata.DEFAULT, "body")
        SoulStore.save(context, soulFile)
        assertTrue(file.exists())
    }

    @Test
    fun `refreshCache updates metadata from file`() {
        val context = MockContext()
        SoulStore.ensureExists(context)
        SoulStore.refreshCache(context)
        assertEquals(SoulMetadata.DEFAULT, SoulStore.cachedMetadata.value)

        val newMeta = SoulMetadata("NewName", "🌟", "cool", "zh")
        SoulStore.save(context, SoulFile(newMeta, "body"))
        SoulStore.refreshCache(context)
        assertEquals(newMeta, SoulStore.cachedMetadata.value)
    }

    @Test
    fun `refreshCache falls back to DEFAULT when file missing`() {
        val context = MockContext()
        SoulStore.fileLocation(context).delete()
        SoulStore.refreshCache(context)
        assertEquals(SoulMetadata.DEFAULT, SoulStore.cachedMetadata.value)
    }

    @Test
    fun `cachedMetadata is initially DEFAULT`() {
        assertEquals(SoulMetadata.DEFAULT, SoulStore.cachedMetadata.value)
    }

    @Test
    fun `isOverLimit handles Japanese characters`() {
        val body = "あ".repeat(2000)
        val result = SoulStore.isOverLimit(body)
        assertTrue(result is SoulBodyLimitCheck.OverLimitChinese)
    }

    @Test
    fun `isOverLimit handles Korean characters`() {
        val body = "한".repeat(2000)
        val result = SoulStore.isOverLimit(body)
        assertTrue(result is SoulBodyLimitCheck.OverLimitChinese)
    }

    private class MockContext : Context() {
        private val tempDir = createTempDir()
        override fun getFilesDir(): File = tempDir
        override fun getApplicationContext(): Context = this
        override fun getAssets() = throw UnsupportedOperationException()
        override fun getResources() = throw UnsupportedOperationException()
        override fun getPackageName() = "com.openminis.app.agent.test"
        override fun getTheme() = throw UnsupportedOperationException()
        override fun getClassLoader() = this::class.java.classLoader
        override fun startActivity(intent: android.content.Intent) = throw UnsupportedOperationException()
        override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) = throw UnsupportedOperationException()
        override fun sendBroadcast(intent: android.content.Intent) = throw UnsupportedOperationException()
        override fun sendBroadcast(intent: android.content.Intent, receiverPermission: String?) = throw UnsupportedOperationException()
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?) = throw UnsupportedOperationException()
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, flags: Int) = throw UnsupportedOperationException()
        override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) = throw UnsupportedOperationException()
        override fun getSystemService(name: String): Any? = throw UnsupportedOperationException()
        override fun checkSelfPermission(permission: String): Int = throw UnsupportedOperationException()
        override fun checkCallingOrSelfPermission(permission: String): Int = throw UnsupportedOperationException()
        override fun getMainLooper() = throw UnsupportedOperationException()
        override fun getMainExecutor() = throw UnsupportedOperationException()
        override fun createContext(display: android.content.res.Configuration) = throw UnsupportedOperationException()
        override fun createDisplayContext(display: android.view.Display) = throw UnsupportedOperationException()
        override fun getPackageManager() = throw UnsupportedOperationException()
        override fun getContentResolver() = throw UnsupportedOperationException()
        override fun getApplicationInfo() = throw UnsupportedOperationException()
        override fun getPackageResourcePath() = throw UnsupportedOperationException()
        override fun getPackageCodePath() = throw UnsupportedOperationException()
        override fun getSharedPreferences(name: String, mode: Int) = throw UnsupportedOperationException()
        override fun openFileInput(name: String) = throw UnsupportedOperationException()
        override fun openFileOutput(name: String, mode: Int) = throw UnsupportedOperationException()
        override fun deleteFile(name: String) = throw UnsupportedOperationException()
        override fun fileList(): Array<String> = throw UnsupportedOperationException()
        override fun getDir(name: String, mode: Int) = throw UnsupportedOperationException()
        override fun getDatabasePath(name: String) = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) = throw UnsupportedOperationException()
        override fun deleteDatabase(name: String) = throw UnsupportedOperationException()
        override fun databaseList(): Array<String> = throw UnsupportedOperationException()
        override fun getWallpaper() = throw UnsupportedOperationException()
        override fun getWallpaperDesiredMinimumWidth() = throw UnsupportedOperationException()
        override fun getWallpaperDesiredMinimumHeight() = throw UnsupportedOperationException()
        override fun setWallpaper(bitmap: android.graphics.Bitmap) = throw UnsupportedOperationException()
        override fun setWallpaper(data: java.io.InputStream) = throw UnsupportedOperationException()
        override fun clearWallpaper() = throw UnsupportedOperationException()
        override fun getObbDir() = throw UnsupportedOperationException()
        override fun getExternalFilesDir(type: String?) = throw UnsupportedOperationException()
        override fun getExternalFilesDirs(type: String?): Array<File> = throw UnsupportedOperationException()
        override fun getExternalCacheDir() = throw UnsupportedOperationException()
        override fun getExternalCacheDirs(): Array<File> = throw UnsupportedOperationException()
        override fun getNoBackupFilesDir() = throw UnsupportedOperationException()
        override fun getCodeCacheDir() = throw UnsupportedOperationException()
        override fun getCacheDir() = throw UnsupportedOperationException()
        override fun isDeviceProtectedStorage() = throw UnsupportedOperationException()
        override fun isCredentialProtectedStorage() = throw UnsupportedOperationException()
        override fun moveDatabaseFrom(source: Context, name: String) = throw UnsupportedOperationException()
        override fun moveSharedPreferencesFrom(source: Context, name: String) = throw UnsupportedOperationException()
        override fun createDeviceProtectedStorageContext() = throw UnsupportedOperationException()
        override fun createCredentialProtectedStorageContext() = throw UnsupportedOperationException()
        override fun getString(resId: Int): String = throw UnsupportedOperationException()
        override fun getString(resId: Int, vararg formatArgs: Any?): String = throw UnsupportedOperationException()
        override fun getText(resId: Int): CharSequence = throw UnsupportedOperationException()
        override fun getText(resId: Int, vararg formatArgs: Any?): CharSequence = throw UnsupportedOperationException()
        override fun getColor(resId: Int): Int = throw UnsupportedOperationException()
        override fun getColorStateList(resId: Int) = throw UnsupportedOperationException()
        override fun getDrawable(resId: Int) = throw UnsupportedOperationException()
        override fun getDimension(resId: Int): Float = throw UnsupportedOperationException()
        override fun getDimensionPixelOffset(resId: Int): Int = throw UnsupportedOperationException()
        override fun getDimensionPixelSize(resId: Int): Int = throw UnsupportedOperationException()
        override fun getInteger(resId: Int): Int = throw UnsupportedOperationException()
        override fun getIntArray(resId: Int): IntArray = throw UnsupportedOperationException()
        override fun getStringArray(resId: Int): Array<String> = throw UnsupportedOperationException()
        override fun getTextArray(resId: Int): Array<CharSequence> = throw UnsupportedOperationException()
        override fun getBoolean(resId: Int): Boolean = throw UnsupportedOperationException()
        override fun obtainStyledAttributes(attrs: IntArray?) = throw UnsupportedOperationException()
        override fun obtainStyledAttributes(resId: Int, attrs: IntArray?) = throw UnsupportedOperationException()
        override fun obtainStyledAttributes(set: android.content.res.TypedArray?, attrs: IntArray?) = throw UnsupportedOperationException()
        override fun getSystemServiceName(serviceClass: Class<*>): String = throw UnsupportedOperationException()
    }
}