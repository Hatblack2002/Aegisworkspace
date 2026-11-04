package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.aegis.virtualcore.GuestMapsReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app_name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Aegis Engine", appName)
    }

    @Test
    fun `GuestMapsReport serializes strict JSON contract for Green state`() {
        val report = GuestMapsReport(
            ok = true,
            pid = 12345,
            packageName = "com.dev.migame",
            libs = listOf("/data/app/.../libgame.so", "/system/lib64/libc.so"),
            error = null,
            processName = ":p0"
        )
        val jsonStr = report.toStrictContractJson()
        val parsed = JSONObject(jsonStr)
        assertTrue(parsed.getBoolean("ok"))
        assertEquals(12345, parsed.getInt("pid"))
        assertEquals("com.dev.migame", parsed.getString("packageName"))
        assertEquals(2, parsed.getJSONArray("libs").length())
        assertTrue(parsed.isNull("error"))
    }

    @Test
    fun `GuestMapsReport serializes strict JSON contract for Red state`() {
        val report = GuestMapsReport(
            ok = false,
            pid = 23456,
            packageName = "com.dev.blocked",
            libs = emptyList(),
            error = "EACCES",
            processName = ":p0"
        )
        val parsed = GuestMapsReport.fromContractOrFullJson(report.toStrictContractJson())
        assertFalse(parsed.ok)
        assertEquals(23456, parsed.pid)
        assertEquals("com.dev.blocked", parsed.packageName)
        assertTrue(parsed.libs.isEmpty())
        assertEquals("EACCES", parsed.error)
    }
}
