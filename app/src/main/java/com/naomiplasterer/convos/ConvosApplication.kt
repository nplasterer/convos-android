package com.naomiplasterer.convos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.util.Log
import java.security.Security

private const val TAG = "ConvosApplication"

@HiltAndroidApp
class ConvosApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Register BouncyCastle as a security provider for secp256k1 signing
        Security.removeProvider("BC") // Remove if already exists
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        Log.d(TAG, "Registered BouncyCastle security provider")

        Log.d(TAG, "Convos Application started")
    }
}
