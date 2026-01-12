package com.naomiplasterer.convos

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.naomiplasterer.convos.codecs.ExplodeSettingsCodec
import com.naomiplasterer.convos.lifecycle.AppLifecycleObserver
import com.naomiplasterer.convos.workers.ExpiredConversationsWorker
import dagger.hilt.android.HiltAndroidApp
import org.xmtp.android.library.Client
import org.xmtp.android.library.codecs.AttachmentCodec
import org.xmtp.android.library.codecs.ReactionCodec
import org.xmtp.android.library.codecs.ReplyCodec
import java.security.Security
import javax.inject.Inject

private const val TAG = "ConvosApplication"

@HiltAndroidApp
class ConvosApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var lifecycleObserver: AppLifecycleObserver

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Register XMTP codecs FIRST, before any client operations
        registerXmtpCodecs()

        // Register BouncyCastle as a security provider for secp256k1 signing
        Security.removeProvider("BC") // Remove if already exists
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        Log.d(TAG, "Registered BouncyCastle security provider")

        // Start lifecycle observer for battery optimization
        lifecycleObserver.start()
        Log.d(TAG, "Started AppLifecycleObserver")

        // Schedule the expired conversations cleanup worker
        ExpiredConversationsWorker.schedule(this)
        Log.d(TAG, "Scheduled ExpiredConversationsWorker")

        Log.d(TAG, "Convos Application started")
    }

    private fun registerXmtpCodecs() {
        try {
            // Register all custom codecs globally before any client operations
            Client.register(codec = AttachmentCodec())
            Client.register(codec = ReactionCodec())
            Client.register(codec = ReplyCodec())
            Client.register(codec = ExplodeSettingsCodec())
            Log.d(TAG, "Registered XMTP codecs: Attachment, Reaction, Reply, ExplodeSettings")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering XMTP codecs", e)
        }
    }
}
