package com.streamlimit.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_VPN_PERMISSION = 2001
        private const val REQ_NOTIF_PERMISSION = 2002

        const val COLOR_BG_PRIMARY = 0xFF0B0E14.toInt()
        const val COLOR_BG_SECONDARY = 0xFF111622.toInt()
        const val COLOR_BG_CARD = 0xFF161C2A.toInt()
        const val COLOR_BG_CARD_ELEVATED = 0xFF1E2638.toInt()
        const val COLOR_BORDER = 0xFF252F45.toInt()
        const val COLOR_INDIGO = 0xFF6366F1.toInt()
        const val COLOR_INDIGO_DARK = 0xFF4338CA.toInt()
        const val COLOR_CYAN = 0xFF06B6D4.toInt()
        const val COLOR_EMERALD = 0xFF10B981.toInt()
        const val COLOR_EMERALD_DARK = 0xFF059669.toInt()
        const val COLOR_AMBER = 0xFFF59E0B.toInt()
        const val COLOR_ROSE = 0xFFEF4444.toInt()
        const val COLOR_TEXT_PRIMARY = 0xFFF8FAFC.toInt()
        const val COLOR_TEXT_SECONDARY = 0xFF94A3B8.toInt()
        const val COLOR_TEXT_MUTED = 0xFF64748B.toInt()
    }

    private lateinit var prefs: AppPreferences
    private val uiHandler = Handler(Looper.getMainLooper())

    // Navigation Views
    private lateinit var contentFrame: FrameLayout
    private lateinit var dashboardPage: View
    private lateinit var appsPage: View
    private lateinit var profilesPage: View
    private lateinit var settingsPage: View
    private val navTabViews = mutableListOf<LinearLayout>()
    private val navTabLabels = mutableListOf<TextView>()
    private var currentTabIndex = 0

    // Header Status Pill
    private lateinit var headerStatusDot: View
    private lateinit var headerStatusText: TextView

    // Dashboard Views
    private lateinit var heroCardLayout: LinearLayout
    private lateinit var heroBadgeText: TextView
    private lateinit var heroTitleText: TextView
    private lateinit var heroSubtitleText: TextView
    private lateinit var heroTimerText: TextView
    private lateinit var mainToggleBtn: Button

    // PC Tethering Isolation Card Views
    private lateinit var pcModeCardLayout: LinearLayout
    private lateinit var pcModeSwitch: Switch
    private lateinit var pcTetherStatusChip: TextView
    private lateinit var pcTetherSystemCountText: TextView

    // Stats Grid Views
    private lateinit var statAllowedAppsValue: TextView
    private lateinit var statAllowedAppsSub: TextView
    private lateinit var statBlockedPacketsValue: TextView
    private lateinit var statBlockedBytesSub: TextView
    private lateinit var statDownloadMbpsValue: TextView
    private lateinit var statUploadMbpsValue: TextView
    private lateinit var lastBlockedPacketBanner: TextView

    // Quick Profile Chips on Dashboard
    private lateinit var dashboardProfileChipContainer: LinearLayout

    // App Selector Views
    private lateinit var appSearchInput: EditText
    private lateinit var filterChipUser: TextView
    private lateinit var filterChipSystem: TextView
    private lateinit var filterChipAllowed: TextView
    private lateinit var appCountSummaryText: TextView
    private lateinit var appListView: ListView
    private lateinit var appListAdapter: InstalledAppAdapter
    private var allInternetApps: List<InstalledAppItem> = emptyList()
    private var filteredApps: List<InstalledAppItem> = emptyList()
    private var currentAppFilterMode = 0 // 0 = User, 1 = System, 2 = Allowed Only

    // Profiles Page Container
    private lateinit var profilesListContainer: LinearLayout

    // Settings Page Views
    private lateinit var switchProtectTethering: Switch
    private lateinit var switchProtectCoreDns: Switch
    private lateinit var switchProtectGms: Switch
    private lateinit var detectedTetheringPackagesText: TextView

    // Live Traffic Speed State
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastTrafficTimestampMs: Long = 0L

    private val liveTicker = object : Runnable {
        override fun run() {
            updateLiveTelemetry()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)

        requestNotificationPermissionIfNeeded()
        initTrafficBaseline()

        val root = buildRootLayout()
        setContentView(root)

        loadInstalledAppsAsync()
        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        initTrafficBaseline()
        uiHandler.removeCallbacks(liveTicker)
        uiHandler.post(liveTicker)
        refreshAllUiStates()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(liveTicker)
    }

    private fun initTrafficBaseline() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        lastRxBytes = if (rx != TrafficStats.UNSUPPORTED.toLong()) rx else 0L
        lastTxBytes = if (tx != TrafficStats.UNSUPPORTED.toLong()) tx else 0L
        lastTrafficTimestampMs = System.currentTimeMillis()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF_PERMISSION)
            }
        }
    }

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG_PRIMARY)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top App Bar
        root.addView(buildTopHeader())

        // Main Content Frame
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        dashboardPage = buildDashboardPage()
        appsPage = buildAppSelectorPage()
        profilesPage = buildProfilesPage()
        settingsPage = buildSettingsPage()

        contentFrame.addView(dashboardPage)
        contentFrame.addView(appsPage)
        contentFrame.addView(profilesPage)
        contentFrame.addView(settingsPage)
        root.addView(contentFrame)

        // Bottom Navigation Bar
        root.addView(buildBottomNavBar())

        return root
    }

    private fun buildTopHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(COLOR_BG_SECONDARY)
        }

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "StreamLimit"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val mobileBadge = TextView(this).apply {
            text = "MOBILE"
            setTextColor(COLOR_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = roundedBg(0x2206B6D4, dp(6), COLOR_CYAN, dp(1))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = dp(8)
            layoutParams = lp
        }

        titleRow.addView(titleText)
        titleRow.addView(mobileBadge)

        val subText = TextView(this).apply {
            text = "Root'suz Ağ İzolasyon & PC Tethering Kalkanı"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        brandCol.addView(titleRow)
        brandCol.addView(subText)

        // Live Status Pill
        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBg(COLOR_BG_CARD, dp(20), COLOR_BORDER, dp(1))
        }

        headerStatusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                rightMargin = dp(6)
            }
            background = roundedBg(COLOR_TEXT_MUTED, dp(4))
        }

        headerStatusText = TextView(this).apply {
            text = "HAZIR"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
        }

        statusPill.addView(headerStatusDot)
        statusPill.addView(headerStatusText)

        header.addView(brandCol)
        header.addView(statusPill)
        return header
    }

    private fun buildDashboardPage(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // 1. HERO ISOLATION CONTROL CARD
        heroCardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBg(COLOR_BG_CARD, dp(16), COLOR_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val topBadgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        heroBadgeText = TextView(this).apply {
            text = "SINKHOLE VPN MOTORU • HAZIR"
            setTextColor(COLOR_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        heroTimerText = TextView(this).apply {
            text = "00:00:00"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBg(COLOR_BG_SECONDARY, dp(8), COLOR_BORDER, dp(1))
        }

        topBadgeRow.addView(heroBadgeText)
        topBadgeRow.addView(heroTimerText)

        heroTitleText = TextView(this).apply {
            text = "İzolasyon Kapalı"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }

        heroSubtitleText = TextView(this).apply {
            text = "Başlattığınızda izinli uygulamalar VPN tünelini tamamen bypass ederek (%100 tam hız, 0 ms ek ping) doğrudan internete çıkar; geri kalan tüm uygulamalar sanal TUN kara deliğinde engellenir."
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, dp(14))
        }

        mainToggleBtn = Button(this).apply {
            text = "🛡️ İZOLASYONU BAŞLAT"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            background = roundedBg(COLOR_INDIGO, dp(12))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                toggleVpnIsolation()
            }
        }

        heroCardLayout.addView(topBadgeRow)
        heroCardLayout.addView(heroTitleText)
        heroCardLayout.addView(heroSubtitleText)
        heroCardLayout.addView(mainToggleBtn)
        container.addView(heroCardLayout)

        // 2. SPECIAL CARD: "SADECE PC'YE İNTERNET VER (PC TETHERING MODU)"
        pcModeCardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBg(COLOR_BG_CARD, dp(16), COLOR_CYAN, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val pcHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pcTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val pcTag = TextView(this).apply {
            text = "ÖZEL İZOLASYON MODU • %100 PC BANT GENİŞLİĞİ"
            setTextColor(COLOR_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val pcTitle = TextView(this).apply {
            text = "💻 Sadece PC'ye İnternet Ver"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        }

        pcTitleCol.addView(pcTag)
        pcTitleCol.addView(pcTitle)

        pcModeSwitch = Switch(this).apply {
            isChecked = prefs.isPcTetheringMode || prefs.activeProfileId == AppPreferences.PROFILE_PC_ONLY
            setOnCheckedChangeListener { _, isChecked ->
                onPcTetheringModeToggled(isChecked)
            }
        }

        pcHeaderRow.addView(pcTitleCol)
        pcHeaderRow.addView(pcModeSwitch)

        val pcDesc = TextView(this).apply {
            text = "Aktif edildiğinde telefondaki HİÇBİR kullanıcı uygulamasına izin verilmez (0 uygulama). Yalnızca Android'in USB Tethering ve Wi-Fi Hotspot sistem paketleri korunur; telefondaki arka plan uygulamaları 1 byte bile harcayamaz."
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(10))
        }

        pcTetherStatusChip = TextView(this).apply {
            text = "🔌 USB Tethering / Hotspot kontrol ediliyor..."
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBg(COLOR_BG_SECONDARY, dp(10), COLOR_BORDER, dp(1))
        }

        pcTetherSystemCountText = TextView(this).apply {
            text = "Korunan Tethering Sistem Paketi: Tespit ediliyor..."
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(8), 0, dp(10))
        }

        val pcActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val openTetherSettingsBtn = Button(this).apply {
            text = "⚙️ Hotspot / USB Tethering Ayarlarını Aç"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            background = roundedBg(COLOR_BG_CARD_ELEVATED, dp(10), COLOR_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                TetheringDetector.openTetheringSettings(this@MainActivity)
            }
        }

        pcActionRow.addView(openTetherSettingsBtn)

        pcModeCardLayout.addView(pcHeaderRow)
        pcModeCardLayout.addView(pcDesc)
        pcModeCardLayout.addView(pcTetherStatusChip)
        pcModeCardLayout.addView(pcTetherSystemCountText)
        pcModeCardLayout.addView(pcActionRow)
        container.addView(pcModeCardLayout)

        // 3. QUICK PROFILE SWITCHER ROW
        val quickProfileTitle = TextView(this).apply {
            text = "HIZLI PROFİL GEÇİŞİ"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(2), 0, dp(8))
        }
        container.addView(quickProfileTitle)

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        dashboardProfileChipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chipScroll.addView(dashboardProfileChipContainer)
        container.addView(chipScroll)

        // 4. TELEMETRY & STATS GRID (2x2)
        val telemetryTitle = TextView(this).apply {
            text = "CANLI AĞ TELEMETRİSİ & SINKHOLE SAYACI"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(8))
        }
        container.addView(telemetryTitle)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // Stat 1: Allowed (Disallowed Bypass) Apps
        val cardAllowed = createStatCard(
            title = "İZİNLİ (BYPASS) UYGULAMA",
            accentColor = COLOR_EMERALD
        )
        statAllowedAppsValue = cardAllowed.second
        statAllowedAppsSub = cardAllowed.third
        cardAllowed.first.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(5)
        }
        cardAllowed.first.setOnClickListener { switchTab(1) }

        // Stat 2: Blocked Sinkhole Packets
        val cardBlocked = createStatCard(
            title = "ENGELLENEN PAKET",
            accentColor = COLOR_ROSE
        )
        statBlockedPacketsValue = cardBlocked.second
        statBlockedBytesSub = cardBlocked.third
        cardBlocked.first.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(5)
        }

        row1.addView(cardAllowed.first)
        row1.addView(cardBlocked.first)
        container.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // Stat 3: Live Download Speed
        val cardDown = createStatCard(
            title = "CANLI İNDİRME (DL)",
            accentColor = COLOR_CYAN
        )
        statDownloadMbpsValue = cardDown.second
        cardDown.third.text = "Doğrudan Hat Hızı (0 ms VPN)"
        cardDown.first.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(5)
        }

        // Stat 4: Live Upload Speed
        val cardUp = createStatCard(
            title = "CANLI YÜKLEME (UL)",
            accentColor = COLOR_INDIGO
        )
        statUploadMbpsValue = cardUp.second
        cardUp.third.text = "Tethering + İzinli Uygulamalar"
        cardUp.first.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(5)
        }

        row2.addView(cardDown.first)
        row2.addView(cardUp.first)
        container.addView(row2)

        // Last blocked packet inspector banner
        lastBlockedPacketBanner = TextView(this).apply {
            text = "Kara Delik (TUN) Paket Denetçisi: İzolasyon başlatıldığında engellenen IP/TCP/UDP paketleri burada canlı görünür."
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(COLOR_BG_SECONDARY, dp(10), COLOR_BORDER, dp(1))
        }
        container.addView(lastBlockedPacketBanner)

        scroll.addView(container)
        return scroll
    }

    private fun createStatCard(
        title: String,
        accentColor: Int
    ): Triple<LinearLayout, TextView, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(COLOR_BG_CARD, dp(14), COLOR_BORDER, dp(1))
        }

        val label = TextView(this).apply {
            text = title
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val value = TextView(this).apply {
            text = "0"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }

        val sub = TextView(this).apply {
            text = "-"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        card.addView(label)
        card.addView(value)
        card.addView(sub)
        return Triple(card, value, sub)
    }

    private fun buildAppSelectorPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val pageTitle = TextView(this).apply {
            text = "İzinli Uygulama Seçici (Whitelist)"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
        }

        appCountSummaryText = TextView(this).apply {
            text = "Uygulamalar taranıyor..."
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }

        titleCol.addView(pageTitle)
        titleCol.addView(appCountSummaryText)

        val clearAllBtn = Button(this).apply {
            text = "Tümünü Temizle"
            setTextColor(COLOR_ROSE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            isAllCaps = false
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBg(COLOR_BG_CARD, dp(8), COLOR_BORDER, dp(1))
            setOnClickListener {
                prefs.setPackagesForActiveProfile(emptySet())
                notifyVpnReloadIfRunning()
                refreshAllUiStates()
                Toast.makeText(this@MainActivity, "Aktif profildeki tüm uygulama izinleri temizlendi", Toast.LENGTH_SHORT).show()
            }
        }

        headerRow.addView(titleCol)
        headerRow.addView(clearAllBtn)
        page.addView(headerRow)

        // Search input
        appSearchInput = EditText(this).apply {
            hint = "🔍 Uygulama adı veya paket adı ara (örn: discord, pubg)..."
            setHintTextColor(COLOR_TEXT_MUTED)
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBg(COLOR_BG_CARD, dp(12), COLOR_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyAppFilter()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        page.addView(appSearchInput)

        // Filter tabs: Kullanıcı Uygulamaları | Sistem Uygulamaları | İzinliler
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        filterChipUser = createFilterTabChip("Kullanıcı Uygulamaları", 0)
        filterChipSystem = createFilterTabChip("Sistem Uygulamaları", 1)
        filterChipAllowed = createFilterTabChip("İzinliler", 2)

        filterRow.addView(filterChipUser)
        filterRow.addView(filterChipSystem)
        filterRow.addView(filterChipAllowed)
        page.addView(filterRow)

        // ListView for smooth virtualized scrolling of hundreds of apps with real icons
        appListView = ListView(this).apply {
            divider = null
            dividerHeight = dp(8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        appListAdapter = InstalledAppAdapter()
        appListView.adapter = appListAdapter
        page.addView(appListView)

        updateFilterChipStyles()
        return page
    }

    private fun createFilterTabChip(label: String, mode: Int): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (mode < 2) rightMargin = dp(6)
            }
            setOnClickListener {
                currentAppFilterMode = mode
                updateFilterChipStyles()
                applyAppFilter()
            }
        }
    }

    private fun updateFilterChipStyles() {
        val chips = listOf(filterChipUser, filterChipSystem, filterChipAllowed)
        chips.forEachIndexed { index, chip ->
            if (index == currentAppFilterMode) {
                chip.setTextColor(Color.WHITE)
                chip.background = roundedBg(COLOR_INDIGO, dp(10))
            } else {
                chip.setTextColor(COLOR_TEXT_SECONDARY)
                chip.background = roundedBg(COLOR_BG_CARD, dp(10), COLOR_BORDER, dp(1))
            }
        }
    }

    private fun buildProfilesPage(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "İzolasyon Profilleri"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val sub = TextView(this).apply {
            text = "Tek dokunuşla senaryoya uygun izin listesine (Whitelist) geçin"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        titleCol.addView(title)
        titleCol.addView(sub)

        val addProfileBtn = Button(this).apply {
            text = "+ Yeni Profil"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            background = roundedBg(COLOR_INDIGO, dp(10))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                showCreateProfileDialog()
            }
        }

        headerRow.addView(titleCol)
        headerRow.addView(addProfileBtn)
        container.addView(headerRow)

        profilesListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(profilesListContainer)

        scroll.addView(container)
        return scroll
    }

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val title = TextView(this).apply {
            text = "Sistem Koruması & Mimari Ayarları"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }

        val sub = TextView(this).apply {
            text = "Root gerektirmeyen VpnService Sinkhole ve Bypass paket koruma tercihleri"
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(14))
        }

        container.addView(title)
        container.addView(sub)

        // Setting 1: Tethering / Hotspot Protection
        val tetherCard = createSettingToggleCard(
            title = "🔌 Tethering / Wi-Fi Hotspot Sistem Koruması",
            description = "USB Tethering ve Wi-Fi Hotspot sistem paketlerini (com.android.networkstack.tethering vb.) otomatik olarak TUN kara deliğinden muaf tutar. Böylece PC'ye paylaşılan internet hiçbir zaman kesilmez.",
            initialChecked = prefs.protectTetheringPackages
        ) { checked ->
            prefs.protectTetheringPackages = checked
            notifyVpnReloadIfRunning()
            refreshAllUiStates()
        }
        switchProtectTethering = tetherCard.second
        container.addView(tetherCard.first)

        // Setting 2: Core System DNS / Connectivity Check Protection
        val dnsCard = createSettingToggleCard(
            title = "🌐 Temel Sistem DNS & Ağ Doğrulama Koruması",
            description = "Android sistem DNS çözümleyicisi ve Captive Portal bağlantı kontrol paketlerini korur. Wi-Fi/Mobil Veri ünlem işareti hatasını önler.",
            initialChecked = prefs.protectCoreDnsPackages
        ) { checked ->
            prefs.protectCoreDnsPackages = checked
            notifyVpnReloadIfRunning()
            refreshAllUiStates()
        }
        switchProtectCoreDns = dnsCard.second
        container.addView(dnsCard.first)

        // Setting 3: Google Play Services (GMS) Protection
        val gmsCard = createSettingToggleCard(
            title = "🔔 Google Play Servisleri (GMS) Bypass",
            description = "Normal profillerde anlık bildirimler (FCM) ve Google oturum doğrulaması için com.google.android.gms paketine izin verir. ('Sadece PC Modu' aktifken GMS dahil her şey otomatik kesilir.)",
            initialChecked = prefs.protectGmsPackages
        ) { checked ->
            prefs.protectGmsPackages = checked
            notifyVpnReloadIfRunning()
            refreshAllUiStates()
        }
        switchProtectGms = gmsCard.second
        container.addView(gmsCard.first)

        // Detected system packages info card
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(COLOR_BG_CARD, dp(14), COLOR_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        val infoTitle = TextView(this).apply {
            text = "CİHAZDA TESPİT EDİLEN TETHERING & SİSTEM PAKETLERİ"
            setTextColor(COLOR_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }

        detectedTetheringPackagesText = TextView(this).apply {
            text = "Taranıyor..."
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        infoCard.addView(infoTitle)
        infoCard.addView(detectedTetheringPackagesText)
        container.addView(infoCard)

        // Architecture explanation card
        val archCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(COLOR_BG_SECONDARY, dp(14), COLOR_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val archTitle = TextView(this).apply {
            text = "⚙️ VpnService Sinkhole + Disallowed Bypass Mimarisi"
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }

        val archDesc = TextView(this).apply {
            text = "• Sanal TUN Arayüzü: 10.255.255.1/32 (IPv4) ve fd00:dec0:de00::1/128 (IPv6)\n" +
                "• İzinli (Whitelist) Uygulamalar: addDisallowedApplication(packageName) ile VPN tünelinden tamamen muaf tutulur. Telefonun gerçek Wi-Fi / 4.5G / 5G arayüzünü %100 ham hızda, 0 ms ek gecikmeyle doğrudan kullanır.\n" +
                "• Engellenen Uygulamalar: Sanal TUN kara deliğine yönlendirilir ve çekirdek düzeyinde 0 byte dış trafikle susturulur."
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }

        archCard.addView(archTitle)
        archCard.addView(archDesc)
        container.addView(archCard)

        scroll.addView(container)
        return scroll
    }

    private fun createSettingToggleCard(
        title: String,
        description: String,
        initialChecked: Boolean,
        onToggle: (Boolean) -> Unit
    ): Pair<LinearLayout, Switch> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(COLOR_BG_CARD, dp(14), COLOR_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggle = Switch(this).apply {
            isChecked = initialChecked
            setOnCheckedChangeListener { _, isChecked ->
                onToggle(isChecked)
            }
        }

        topRow.addView(titleView)
        topRow.addView(toggle)

        val descView = TextView(this).apply {
            text = description
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        }

        card.addView(topRow)
        card.addView(descView)
        return Pair(card, toggle)
    }

    private fun buildBottomNavBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_BG_SECONDARY)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val items = listOf(
            "⚡ Panel" to 0,
            "📱 Uygulamalar" to 1,
            "🎯 Profiller" to 2,
            "⚙️ Korumalar" to 3
        )

        navTabViews.clear()
        navTabLabels.clear()

        for ((label, idx) in items) {
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener { switchTab(idx) }
            }

            val txt = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
            }

            tab.addView(txt)
            navTabViews.add(tab)
            navTabLabels.add(txt)
            bar.addView(tab)
        }

        return bar
    }

    private fun switchTab(index: Int) {
        currentTabIndex = index
        dashboardPage.visibility = if (index == 0) View.VISIBLE else View.GONE
        appsPage.visibility = if (index == 1) View.VISIBLE else View.GONE
        profilesPage.visibility = if (index == 2) View.VISIBLE else View.GONE
        settingsPage.visibility = if (index == 3) View.VISIBLE else View.GONE

        navTabViews.forEachIndexed { i, tab ->
            val label = navTabLabels[i]
            if (i == index) {
                tab.background = roundedBg(COLOR_INDIGO, dp(10))
                label.setTextColor(Color.WHITE)
            } else {
                tab.background = roundedBg(Color.TRANSPARENT, dp(10))
                label.setTextColor(COLOR_TEXT_SECONDARY)
            }
        }

        refreshAllUiStates()
    }

    private fun loadInstalledAppsAsync() {
        thread(start = true) {
            val apps = AppRepository.getInternetApps(applicationContext, forceRefresh = true)
            uiHandler.post {
                allInternetApps = apps
                applyAppFilter()
                refreshAllUiStates()
            }
        }
    }

    private fun applyAppFilter() {
        val query = appSearchInput.text?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
        val activeAllowed = prefs.getActiveProfile().allowedPackages

        filteredApps = allInternetApps.filter { item ->
            val matchesMode = when (currentAppFilterMode) {
                0 -> !item.isSystemApp
                1 -> item.isSystemApp
                2 -> activeAllowed.contains(item.packageName)
                else -> true
            }
            val matchesQuery = query.isEmpty() ||
                item.appName.lowercase(Locale.getDefault()).contains(query) ||
                item.packageName.lowercase(Locale.getDefault()).contains(query)

            matchesMode && matchesQuery
        }

        val pcMode = prefs.isPcTetheringMode || prefs.activeProfileId == AppPreferences.PROFILE_PC_ONLY
        appCountSummaryText.text = if (pcMode) {
            "💻 Sadece PC Modu aktif: Tüm kullanıcı uygulamaları engelli (${filteredApps.size} listeleniyor)"
        } else {
            "Aktif Profil: ${prefs.getActiveProfile().name} • ${activeAllowed.size} izinli uygulama (${filteredApps.size} gösteriliyor)"
        }

        appListAdapter.notifyDataSetChanged()
    }

    private fun toggleVpnIsolation() {
        if (StreamLimitVpnService.isRunning.get()) {
            val stopIntent = Intent(this, StreamLimitVpnService::class.java).apply {
                action = StreamLimitVpnService.ACTION_STOP
            }
            startService(stopIntent)
            uiHandler.postDelayed({ refreshAllUiStates() }, 200L)
        } else {
            startVpnWithPermissionCheck()
        }
    }

    private fun startVpnWithPermissionCheck() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQ_VPN_PERMISSION)
        } else {
            onActivityResult(REQ_VPN_PERMISSION, Activity.RESULT_OK, null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                val startIntent = Intent(this, StreamLimitVpnService::class.java).apply {
                    action = StreamLimitVpnService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
                uiHandler.postDelayed({ refreshAllUiStates() }, 250L)
            } else {
                Toast.makeText(
                    this,
                    "İzolasyon için Android VPN iznini onaylamanız gerekiyor.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onPcTetheringModeToggled(enabled: Boolean) {
        prefs.isPcTetheringMode = enabled
        if (enabled) {
            prefs.activeProfileId = AppPreferences.PROFILE_PC_ONLY
            // Automatically start or reload VPN isolation when user turns on "Sadece PC'ye İnternet Ver"
            if (StreamLimitVpnService.isRunning.get()) {
                notifyVpnReloadIfRunning()
            } else {
                startVpnWithPermissionCheck()
            }
        } else {
            if (prefs.activeProfileId == AppPreferences.PROFILE_PC_ONLY) {
                prefs.activeProfileId = AppPreferences.PROFILE_GAMING
            }
            notifyVpnReloadIfRunning()
        }
        refreshAllUiStates()
    }

    private fun notifyVpnReloadIfRunning() {
        if (StreamLimitVpnService.isRunning.get()) {
            val reloadIntent = Intent(this, StreamLimitVpnService::class.java).apply {
                action = StreamLimitVpnService.ACTION_RELOAD
            }
            startService(reloadIntent)
        }
    }

    private fun refreshAllUiStates() {
        val running = StreamLimitVpnService.isRunning.get()
        val pcMode = prefs.isPcTetheringMode || prefs.activeProfileId == AppPreferences.PROFILE_PC_ONLY
        val activeProfile = prefs.getActiveProfile()
        val systemBypassPkgs = prefs.getInstalledSystemBypassPackages(forPcTetheringMode = pcMode)

        // Header status pill
        if (running) {
            headerStatusDot.background = roundedBg(if (pcMode) COLOR_CYAN else COLOR_EMERALD, dp(4))
            headerStatusText.text = if (pcMode) "SADECE PC AKTİF" else "İZOLASYON AKTİF"
            headerStatusText.setTextColor(if (pcMode) COLOR_CYAN else COLOR_EMERALD)
        } else {
            headerStatusDot.background = roundedBg(COLOR_TEXT_MUTED, dp(4))
            headerStatusText.text = "HAZIR"
            headerStatusText.setTextColor(COLOR_TEXT_SECONDARY)
        }

        // Hero card
        if (running) {
            val borderColor = if (pcMode) COLOR_CYAN else COLOR_EMERALD
            heroCardLayout.background = roundedBg(COLOR_BG_CARD, dp(16), borderColor, dp(2))
            heroBadgeText.text = if (pcMode) {
                "SADECE PC MODU AKTİF • TÜM TELEFON KESİLDİ"
            } else {
                "SINKHOLE VPN AKTİF • ${activeProfile.name.uppercase(Locale.getDefault())}"
            }
            heroBadgeText.setTextColor(borderColor)
            heroTitleText.text = if (pcMode) {
                "💻 Sadece PC'ye İnternet Veriliyor"
            } else {
                "🛡️ ${activeProfile.name} Aktif"
            }
            heroSubtitleText.text = if (pcMode) {
                "Telefondaki tüm kullanıcı uygulamaları (%100) sanal TUN kara deliğinde engelleniyor. Yalnızca ${systemBypassPkgs.size} sistem Tethering/Hotspot paketi doğrudan internete çıkıyor."
            } else {
                "${activeProfile.allowedPackages.size} izinli uygulama VPN tünelini bypass ederek %100 tam hızda ve 0 ms ek gecikmeyle doğrudan internete çıkıyor. Diğer tüm uygulamalar engelli."
            }
            mainToggleBtn.text = "⏹️ İZOLASYONU DURDUR (ACİL AÇ)"
            mainToggleBtn.background = roundedBg(COLOR_ROSE, dp(12))
        } else {
            heroCardLayout.background = roundedBg(COLOR_BG_CARD, dp(16), COLOR_BORDER, dp(1))
            heroBadgeText.text = "SINKHOLE VPN MOTORU • BEKLEMEDE"
            heroBadgeText.setTextColor(COLOR_CYAN)
            heroTitleText.text = "İzolasyon Kapalı"
            heroSubtitleText.text = "Seçili Profil: ${activeProfile.iconEmoji} ${activeProfile.name}. Başlattığınızda izinli uygulamalar dışındaki tüm arka plan trafiği çekirdek düzeyinde kesilir."
            mainToggleBtn.text = if (pcMode) {
                "💻 SADECE PC İZOLASYONUNU BAŞLAT"
            } else {
                "🛡️ İZOLASYONU BAŞLAT (${activeProfile.name})"
            }
            mainToggleBtn.background = roundedBg(COLOR_INDIGO, dp(12))
        }

        // PC Mode Switch sync without recursion
        pcModeSwitch.setOnCheckedChangeListener(null)
        pcModeSwitch.isChecked = pcMode
        pcModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            onPcTetheringModeToggled(isChecked)
        }

        pcModeCardLayout.background = roundedBg(
            COLOR_BG_CARD,
            dp(16),
            if (pcMode) COLOR_CYAN else COLOR_BORDER,
            dp(if (pcMode) 2 else 1)
        )
        pcTetherSystemCountText.text =
            "Cihazda korunan Tethering/Sistem paketi: ${systemBypassPkgs.size} adet (${systemBypassPkgs.joinToString(", ").ifEmpty { "Standart Linux Tethering" }})"

        // Rebuild Quick Profile Chips on Dashboard
        rebuildDashboardProfileChips()

        // Rebuild Profiles Page List
        rebuildProfilesPageCards()

        // Refresh App Selector List
        applyAppFilter()

        // Settings Page detected packages
        detectedTetheringPackagesText.text = if (systemBypassPkgs.isEmpty()) {
            "• Kernel Tethering (Ağ arayüzü üzerinden doğrudan yönlendirme)"
        } else {
            systemBypassPkgs.joinToString("\n") { "• $it (addDisallowedApplication ile korunuyor)" }
        }

        updateLiveTelemetry()
    }

    private fun rebuildDashboardProfileChips() {
        dashboardProfileChipContainer.removeAllViews()
        val profiles = prefs.getProfiles()
        val activeId = if (prefs.isPcTetheringMode) AppPreferences.PROFILE_PC_ONLY else prefs.activeProfileId

        for (p in profiles) {
            val isSelected = p.id == activeId
            val chip = TextView(this).apply {
                text = "${p.iconEmoji} ${p.name}" + if (p.isPcTetheringOnly) " (0 Uygulama)" else " (${p.allowedPackages.size})"
                setTextColor(if (isSelected) Color.WHITE else COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = if (isSelected) {
                    roundedBg(if (p.isPcTetheringOnly) COLOR_CYAN else COLOR_INDIGO, dp(20))
                } else {
                    roundedBg(COLOR_BG_CARD, dp(20), COLOR_BORDER, dp(1))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }

                setOnClickListener {
                    selectProfile(p)
                }
            }
            dashboardProfileChipContainer.addView(chip)
        }
    }

    private fun rebuildProfilesPageCards() {
        profilesListContainer.removeAllViews()
        val profiles = prefs.getProfiles()
        val activeId = if (prefs.isPcTetheringMode) AppPreferences.PROFILE_PC_ONLY else prefs.activeProfileId

        for (profile in profiles) {
            val isSelected = profile.id == activeId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = roundedBg(
                    COLOR_BG_CARD,
                    dp(14),
                    if (isSelected) (if (profile.isPcTetheringOnly) COLOR_CYAN else COLOR_INDIGO) else COLOR_BORDER,
                    dp(if (isSelected) 2 else 1)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                setOnClickListener { selectProfile(profile) }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameView = TextView(this).apply {
                text = "${profile.iconEmoji}  ${profile.name}"
                setTextColor(COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusBadge = TextView(this).apply {
                text = if (isSelected) "AKTİF PROFİL" else "SEÇ"
                setTextColor(if (isSelected) Color.WHITE else COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = if (isSelected) {
                    roundedBg(if (profile.isPcTetheringOnly) COLOR_CYAN else COLOR_INDIGO, dp(8))
                } else {
                    roundedBg(COLOR_BG_SECONDARY, dp(8), COLOR_BORDER, dp(1))
                }
            }

            topRow.addView(nameView)
            topRow.addView(statusBadge)

            val descView = TextView(this).apply {
                text = profile.description
                setTextColor(COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, dp(10))
            }

            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val countText = TextView(this).apply {
                text = if (profile.isPcTetheringOnly) {
                    "🔒 0 Kullanıcı Uygulaması • Sadece Tethering & Hotspot"
                } else {
                    "✅ ${profile.allowedPackages.size} İzinli Uygulama (Whitelist)"
                }
                setTextColor(if (profile.isPcTetheringOnly) COLOR_CYAN else COLOR_EMERALD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            bottomRow.addView(countText)

            if (!profile.isPcTetheringOnly) {
                val editAppsBtn = Button(this).apply {
                    text = "Uygulamaları Seç"
                    setTextColor(COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    isAllCaps = false
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = roundedBg(COLOR_BG_CARD_ELEVATED, dp(8), COLOR_BORDER, dp(1))
                    setOnClickListener {
                        selectProfile(profile)
                        switchTab(1)
                    }
                }
                bottomRow.addView(editAppsBtn)
            }

            if (!profile.isBuiltIn) {
                val delBtn = Button(this).apply {
                    text = "Sil"
                    setTextColor(COLOR_ROSE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    isAllCaps = false
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = roundedBg(COLOR_BG_SECONDARY, dp(8), COLOR_BORDER, dp(1))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(6) }
                    setOnClickListener {
                        prefs.deleteProfile(profile.id)
                        notifyVpnReloadIfRunning()
                        refreshAllUiStates()
                    }
                }
                bottomRow.addView(delBtn)
            }

            card.addView(topRow)
            card.addView(descView)
            card.addView(bottomRow)
            profilesListContainer.addView(card)
        }
    }

    private fun selectProfile(profile: ProfileModel) {
        prefs.activeProfileId = profile.id
        prefs.isPcTetheringMode = profile.isPcTetheringOnly
        notifyVpnReloadIfRunning()
        refreshAllUiStates()
    }

    private fun showCreateProfileDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setBackgroundColor(COLOR_BG_CARD)
        }

        val nameInput = EditText(this).apply {
            hint = "Profil Adı (örn: Gece İndirme / Turnuva)"
            setHintTextColor(COLOR_TEXT_MUTED)
            setTextColor(COLOR_TEXT_PRIMARY)
            setSingleLine(true)
        }

        val descInput = EditText(this).apply {
            hint = "Kısa Açıklama"
            setHintTextColor(COLOR_TEXT_MUTED)
            setTextColor(COLOR_TEXT_PRIMARY)
            setSingleLine(true)
        }

        val copyCheck = CheckBox(this).apply {
            text = "Mevcut profilin izinli uygulamalarını kopyala"
            setTextColor(COLOR_TEXT_SECONDARY)
            isChecked = true
        }

        dialogLayout.addView(nameInput)
        dialogLayout.addView(descInput)
        dialogLayout.addView(copyCheck)

        AlertDialog.Builder(this)
            .setTitle("Yeni İzolasyon Profili Oluştur")
            .setView(dialogLayout)
            .setPositiveButton("Oluştur") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    prefs.addCustomProfile(
                        name = name,
                        description = descInput.text?.toString()?.trim().orEmpty(),
                        copyCurrentWhitelist = copyCheck.isChecked
                    )
                    notifyVpnReloadIfRunning()
                    refreshAllUiStates()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun updateLiveTelemetry() {
        val running = StreamLimitVpnService.isRunning.get()
        val pcMode = prefs.isPcTetheringMode || prefs.activeProfileId == AppPreferences.PROFILE_PC_ONLY
        val activeProfile = prefs.getActiveProfile()

        // 1. Active Duration Timer
        if (running && StreamLimitVpnService.sessionStartTimeMs.get() > 0L) {
            val elapsedSec = ((System.currentTimeMillis() - StreamLimitVpnService.sessionStartTimeMs.get()) / 1000L).coerceAtLeast(0L)
            val hrs = elapsedSec / 3600
            val mins = (elapsedSec % 3600) / 60
            val secs = elapsedSec % 60
            heroTimerText.text = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
            heroTimerText.setTextColor(COLOR_EMERALD)
        } else {
            heroTimerText.text = "00:00:00"
            heroTimerText.setTextColor(COLOR_TEXT_SECONDARY)
        }

        // 2. Live USB Tethering / Wi-Fi Hotspot interface detection
        val tetherStatus = TetheringDetector.detectStatus()
        if (tetherStatus.isAnyActive) {
            pcTetherStatusChip.text = "🟢 ${tetherStatus.getSummaryText()}"
            pcTetherStatusChip.setTextColor(COLOR_EMERALD)
            pcTetherStatusChip.background = roundedBg(0x1A10B981, dp(10), COLOR_EMERALD, dp(1))
        } else {
            pcTetherStatusChip.text = "🟠 ${tetherStatus.getSummaryText()}"
            pcTetherStatusChip.setTextColor(COLOR_AMBER)
            pcTetherStatusChip.background = roundedBg(COLOR_BG_SECONDARY, dp(10), COLOR_BORDER, dp(1))
        }

        // 3. Allowed Apps & Blocked Packets Counters
        val allowedUserCount = if (pcMode) 0 else activeProfile.allowedPackages.size
        val sysCount = prefs.getInstalledSystemBypassPackages(forPcTetheringMode = pcMode).size
        statAllowedAppsValue.text = if (pcMode) "0 (Sadece PC)" else "$allowedUserCount Uygulama"
        statAllowedAppsSub.text = "+$sysCount Tethering/Sistem Bypass"

        val blockedPkts = StreamLimitVpnService.blockedPacketsTotal.get()
        val blockedBytes = StreamLimitVpnService.blockedBytesTotal.get()
        val tcpPkts = StreamLimitVpnService.blockedTcpPackets.get()
        val udpPkts = StreamLimitVpnService.blockedUdpPackets.get()

        statBlockedPacketsValue.text = String.format(Locale.US, "%,d", blockedPkts)
        statBlockedBytesSub.text = "${formatBytes(blockedBytes)} (TCP:$tcpPkts • UDP:$udpPkts)"

        lastBlockedPacketBanner.text = if (running) {
            "🛡️ Kara Delik (TUN): ${StreamLimitVpnService.lastBlockedProtocolSummary}"
        } else {
            "Kara Delik (TUN) Paket Denetçisi: İzolasyon başlatıldığında engellenen IP/TCP/UDP paketleri burada canlı görünür."
        }

        // 4. Live Network Speed via TrafficStats (Mbps)
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val dtSec = ((now - lastTrafficTimestampMs) / 1000.0).coerceAtLeast(0.25)

        if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong() && lastTrafficTimestampMs > 0L) {
            val dRx = (rx - lastRxBytes).coerceAtLeast(0L)
            val dTx = (tx - lastTxBytes).coerceAtLeast(0L)

            val downMbps = (dRx * 8.0) / (dtSec * 1_000_000.0)
            val upMbps = (dTx * 8.0) / (dtSec * 1_000_000.0)

            statDownloadMbpsValue.text = String.format(Locale.US, "%.2f Mbps", downMbps)
            statUploadMbpsValue.text = String.format(Locale.US, "%.2f Mbps", upMbps)
        } else {
            statDownloadMbpsValue.text = "0.00 Mbps"
            statUploadMbpsValue.text = "0.00 Mbps"
        }

        lastRxBytes = if (rx != TrafficStats.UNSUPPORTED.toLong()) rx else 0L
        lastTxBytes = if (tx != TrafficStats.UNSUPPORTED.toLong()) tx else 0L
        lastTrafficTimestampMs = now
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private inner class InstalledAppAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredApps.size
        override fun getItem(position: Int): InstalledAppItem = filteredApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: ViewHolder
            val rowView: LinearLayout

            if (convertView == null) {
                rowView = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }

                val iconView = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        rightMargin = dp(12)
                    }
                }

                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameText = TextView(this@MainActivity).apply {
                    setTextColor(COLOR_TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                val pkgText = TextView(this@MainActivity).apply {
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                val stateBadge = TextView(this@MainActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, 0)
                }

                textCol.addView(nameText)
                textCol.addView(pkgText)
                textCol.addView(stateBadge)

                val checkBox = CheckBox(this@MainActivity)

                rowView.addView(iconView)
                rowView.addView(textCol)
                rowView.addView(checkBox)

                holder = ViewHolder(rowView, iconView, nameText, pkgText, stateBadge, checkBox)
                rowView.tag = holder
            } else {
                rowView = convertView as LinearLayout
                holder = rowView.tag as ViewHolder
            }

            val item = getItem(position)
            val pcMode = prefs.isPcTetheringMode || prefs.activeProfileId == AppPreferences.PROFILE_PC_ONLY
            val isAllowed = !pcMode && prefs.getActiveProfile().allowedPackages.contains(item.packageName)

            holder.nameText.text = item.appName
            holder.pkgText.text = item.packageName

            val icon = AppRepository.getAppIcon(this@MainActivity, item.packageName)
            if (icon != null) {
                holder.iconView.setImageDrawable(icon)
            } else {
                holder.iconView.setImageResource(R.drawable.ic_shield_notif)
            }

            if (isAllowed) {
                holder.stateBadge.text = "✅ İZİNLİ • VPN Bypass (%100 Doğrudan İnternet)"
                holder.stateBadge.setTextColor(COLOR_EMERALD)
                rowView.background = roundedBg(COLOR_BG_CARD, dp(12), COLOR_EMERALD, dp(1))
            } else {
                holder.stateBadge.text = "🚫 ENGELLİ • Sinkhole TUN Kara Deliğinde"
                holder.stateBadge.setTextColor(COLOR_TEXT_MUTED)
                rowView.background = roundedBg(COLOR_BG_CARD, dp(12), COLOR_BORDER, dp(1))
            }

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = isAllowed

            val toggleAction = View.OnClickListener {
                prefs.togglePackageInActiveProfile(item.packageName)
                notifyVpnReloadIfRunning()
                refreshAllUiStates()
            }

            rowView.setOnClickListener(toggleAction)
            holder.checkBox.setOnClickListener(toggleAction)

            return rowView
        }
    }

    private class ViewHolder(
        val row: LinearLayout,
        val iconView: ImageView,
        val nameText: TextView,
        val pkgText: TextView,
        val stateBadge: TextView,
        val checkBox: CheckBox
    )

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun roundedBg(
        fillColor: Int,
        radiusPx: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthPx: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }
}
