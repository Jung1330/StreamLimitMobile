package com.streamlimit.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StreamLimitVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.streamlimit.mobile.ACTION_START"
        const val ACTION_STOP = "com.streamlimit.mobile.ACTION_STOP"
        const val ACTION_RELOAD = "com.streamlimit.mobile.ACTION_RELOAD"

        private const val NOTIFICATION_CHANNEL_ID = "streamlimit_isolation_channel"
        private const val NOTIFICATION_ID = 4041

        val isRunning = AtomicBoolean(false)
        val isPcOnlyActive = AtomicBoolean(false)
        val sessionStartTimeMs = AtomicLong(0L)

        val blockedPacketsTotal = AtomicLong(0L)
        val blockedBytesTotal = AtomicLong(0L)
        val blockedTcpPackets = AtomicLong(0L)
        val blockedUdpPackets = AtomicLong(0L)
        val blockedOtherPackets = AtomicLong(0L)
        val activeBypassUserCount = AtomicLong(0L)
        val activeBypassSystemCount = AtomicLong(0L)

        @Volatile
        var lastBlockedProtocolSummary: String = "Henüz paket engellenmedi"

        fun resetCounters() {
            blockedPacketsTotal.set(0L)
            blockedBytesTotal.set(0L)
            blockedTcpPackets.set(0L)
            blockedUdpPackets.set(0L)
            blockedOtherPackets.set(0L)
            lastBlockedProtocolSummary = "İzolasyon aktif • Kara delik hazır"
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private val threadRunning = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnIsolation()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (isRunning.get()) {
                    establishSinkholeTunnel(resetStats = false)
                }
                return START_STICKY
            }
            else -> {
                establishSinkholeTunnel(resetStats = !isRunning.get())
                return START_STICKY
            }
        }
    }

    private fun establishSinkholeTunnel(resetStats: Boolean) {
        val prefs = AppPreferences(this)
        val pcMode = prefs.isPcTetheringMode || prefs.activeProfileId == AppPreferences.PROFILE_PC_ONLY
        val activeProfile = prefs.getActiveProfile()

        if (resetStats) {
            resetCounters()
            sessionStartTimeMs.set(System.currentTimeMillis())
        }

        isPcOnlyActive.set(pcMode)

        // Stop previous reader thread and close previous TUN descriptor if reloading
        stopReaderAndCloseTunnel()

        val builder = Builder()
            .setSession(
                if (pcMode) "StreamLimit: Sadece PC (Tethering)"
                else "StreamLimit: ${activeProfile.name}"
            )
            .setMtu(1500)
            .addAddress("10.255.255.1", 32)
            .addRoute("0.0.0.0", 0)

        try {
            builder.addAddress("fd00:dec0:de00::1", 128)
            builder.addRoute("::", 0)
            builder.allowFamily(OsConstants.AF_INET)
            builder.allowFamily(OsConstants.AF_INET6)
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val systemBypassSet = prefs.getInstalledSystemBypassPackages(forPcTetheringMode = pcMode)
        val userAllowedSet = if (pcMode) emptySet() else activeProfile.allowedPackages

        var userBypassApplied = 0
        var systemBypassApplied = 0

        // Add system tethering / core packages to Disallowed (so they bypass the sinkhole and work at 100% speed)
        for (sysPkg in systemBypassSet) {
            try {
                builder.addDisallowedApplication(sysPkg)
                systemBypassApplied++
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }

        // Add whitelisted user apps to Disallowed (only when NOT in PC-Only Tethering Mode)
        for (userPkg in userAllowedSet) {
            if (systemBypassSet.contains(userPkg)) continue
            try {
                builder.addDisallowedApplication(userPkg)
                userBypassApplied++
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }

        activeBypassUserCount.set(userBypassApplied.toLong())
        activeBypassSystemCount.set(systemBypassApplied.toLong())

        val notification = buildForegroundNotification(
            pcMode = pcMode,
            profileName = activeProfile.name,
            userBypassCount = userBypassApplied,
            systemBypassCount = systemBypassApplied
        )

        startForegroundCompat(notification)

        try {
            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                isRunning.set(true)
                startSinkholeDrainThread(vpnInterface!!)
            } else {
                isRunning.set(false)
                stopSelf()
            }
        } catch (e: Exception) {
            isRunning.set(false)
            stopSelf()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSinkholeDrainThread(pfd: ParcelFileDescriptor) {
        threadRunning.set(true)
        readerThread = Thread({
            val buffer = ByteArray(32767)
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    while (threadRunning.get() && !Thread.currentThread().isInterrupted) {
                        val length = input.read(buffer)
                        if (length > 0) {
                            inspectAndCountDroppedPacket(buffer, length)
                        } else if (length < 0) {
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // Descriptor closed or interrupted during stop/reload
            }
        }, "StreamLimit-Sinkhole-Drain").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Parses the IPv4 / IPv6 header of each packet captured by the Sinkhole TUN interface
     * before discarding it, updating real-time TCP / UDP / Byte counters.
     */
    private fun inspectAndCountDroppedPacket(packet: ByteArray, length: Int) {
        blockedPacketsTotal.incrementAndGet()
        blockedBytesTotal.addAndGet(length.toLong())

        if (length < 1) return
        val version = (packet[0].toInt() ushr 4) and 0x0F

        if (version == 4 && length >= 20) {
            val protocol = packet[9].toInt() and 0xFF
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            var dstPort = 0
            if (length >= ihl + 4 && (protocol == 6 || protocol == 17)) {
                dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            }
            when (protocol) {
                6 -> {
                    blockedTcpPackets.incrementAndGet()
                    lastBlockedProtocolSummary = "ENGEL: TCPv4 -> $dstIp:$dstPort ($length B)"
                }
                17 -> {
                    blockedUdpPackets.incrementAndGet()
                    lastBlockedProtocolSummary = "ENGEL: UDPv4 -> $dstIp:$dstPort ($length B)"
                }
                else -> {
                    blockedOtherPackets.incrementAndGet()
                    lastBlockedProtocolSummary = "ENGEL: IPv4 Proto-$protocol -> $dstIp ($length B)"
                }
            }
        } else if (version == 6 && length >= 40) {
            val nextHeader = packet[6].toInt() and 0xFF
            when (nextHeader) {
                6 -> {
                    blockedTcpPackets.incrementAndGet()
                    lastBlockedProtocolSummary = "ENGEL: TCPv6 Paketi ($length B)"
                }
                17 -> {
                    blockedUdpPackets.incrementAndGet()
                    lastBlockedProtocolSummary = "ENGEL: UDPv6 Paketi ($length B)"
                }
                else -> {
                    blockedOtherPackets.incrementAndGet()
                    lastBlockedProtocolSummary = "ENGEL: IPv6 Next-$nextHeader ($length B)"
                }
            }
        } else {
            blockedOtherPackets.incrementAndGet()
        }
    }

    private fun stopReaderAndCloseTunnel() {
        threadRunning.set(false)
        try {
            readerThread?.interrupt()
        } catch (_: Exception) {
        }
        readerThread = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    private fun stopVpnIsolation() {
        isRunning.set(false)
        isPcOnlyActive.set(false)
        stopReaderAndCloseTunnel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    override fun onRevoke() {
        stopVpnIsolation()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnIsolation()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "StreamLimit Ağ İzolasyon Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aktif ağ izolasyonu ve PC Tethering koruma durumu"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(
        pcMode: Boolean,
        profileName: String,
        userBypassCount: Int,
        systemBypassCount: Int
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StreamLimitVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (pcMode) {
            "💻 Sadece PC Modu (Tethering İzolasyonu) Aktif"
        } else {
            "🛡️ İzolasyon Aktif: $profileName"
        }

        val contentText = if (pcMode) {
            "Telefondaki tüm uygulamalar kesildi • $systemBypassCount Tethering paketi korunuyor"
        } else {
            "$userBypassCount izinli uygulama (%100 doğrudan hız) • Diğer tüm trafik engelli"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_notif)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF6366F1.toInt())
            .setContentIntent(openPending)
            .addAction(
                R.drawable.ic_shield_notif,
                "İzolasyonu Durdur (Acil Aç)",
                stopPending
            )
            .build()
    }
}
