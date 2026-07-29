package com.example.zuppon.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

object NotificationPermission {

    fun register(activity: ComponentActivity): () -> Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return { }
        }
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ -> }
        return {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
