package com.example.agentchat.data.location

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionTest {
    @Test
    fun eitherFineOrCoarsePermissionIsEnoughForWeatherLocation() {
        assertTrue(LocationPermission.isGranted { it == Manifest.permission.ACCESS_FINE_LOCATION })
        assertTrue(LocationPermission.isGranted { it == Manifest.permission.ACCESS_COARSE_LOCATION })
        assertFalse(LocationPermission.isGranted { false })
    }
}
