package com.application.motium.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionManager {
    companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        const val BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 1002
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1003
        const val ACTIVITY_RECOGNITION_REQUEST_CODE = 1004

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            Manifest.permission.WAKE_LOCK
        )

        private val BACKGROUND_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            emptyArray()
        }

        private val NOTIFICATION_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

        private val ACTIVITY_RECOGNITION_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            emptyArray()
        }

        fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasBackgroundLocationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not needed for older versions
            }
        }

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not needed for older versions
            }
        }

        fun hasActivityRecognitionPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not needed for older versions
            }
        }

        fun hasAllRequiredPermissions(context: Context): Boolean {
            return hasLocationPermission(context) &&
                   hasBackgroundLocationPermission(context) &&
                   hasNotificationPermission(context) &&
                   hasActivityRecognitionPermission(context)
        }

        fun requestLocationPermissions(activity: Activity) {
            ActivityCompat.requestPermissions(
                activity,
                REQUIRED_PERMISSIONS,
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        fun requestBackgroundLocationPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.requestPermissions(
                    activity,
                    BACKGROUND_PERMISSIONS,
                    BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }

        fun requestNotificationPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    activity,
                    NOTIFICATION_PERMISSIONS,
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }

        fun requestActivityRecognitionPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.requestPermissions(
                    activity,
                    ACTIVITY_RECOGNITION_PERMISSIONS,
                    ACTIVITY_RECOGNITION_REQUEST_CODE
                )
            }
        }

        fun requestAllPermissions(activity: Activity) {
            // Request basic permissions first
            val permissionsToRequest = mutableListOf<String>()

            REQUIRED_PERMISSIONS.forEach { permission ->
                if (ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission)
                }
            }

            NOTIFICATION_PERMISSIONS.forEach { permission ->
                if (ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission)
                }
            }

            ACTIVITY_RECOGNITION_PERMISSIONS.forEach { permission ->
                if (ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission)
                }
            }

            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    activity,
                    permissionsToRequest.toTypedArray(),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }

        fun handlePermissionResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray,
            onPermissionGranted: () -> Unit,
            onPermissionDenied: () -> Unit
        ) {
            when (requestCode) {
                LOCATION_PERMISSION_REQUEST_CODE,
                BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE,
                NOTIFICATION_PERMISSION_REQUEST_CODE,
                ACTIVITY_RECOGNITION_REQUEST_CODE -> {
                    if (grantResults.isNotEmpty() &&
                        grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                        onPermissionGranted()
                    } else {
                        onPermissionDenied()
                    }
                }
            }
        }
    }
}