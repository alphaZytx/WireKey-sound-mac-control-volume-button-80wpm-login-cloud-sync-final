package com.wirekey.bluetooth

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionManagerTest {

    @Test
    fun testRequiredPermissionsBasedOnSdk() {
        val permissions = PermissionManager.requiredPermissions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val expected = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            assertArrayEquals("Modern Android should require S-level bluetooth permissions", expected, permissions)
        } else {
            val expected = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            assertArrayEquals("Legacy Android should require ACCESS_FINE_LOCATION", expected, permissions)
        }
    }
}
