package com.naomiplasterer.convos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.util.Log

private const val TAG = "ConvosApplication"

@HiltAndroidApp
class ConvosApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Convos Application started")
    }
}
