package com.example.agentchat.data.location

import android.Manifest

object LocationPermission {
    val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun isGranted(hasPermission: (String) -> Boolean): Boolean = permissions.any(hasPermission)
}
