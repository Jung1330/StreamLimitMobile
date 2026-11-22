package com.streamlimit.mobile

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

data class ProfileModel(
    val id: String,
    val name: String,
    val description: String,
    val iconEmoji: String,
    val isPcTetheringOnly: Boolean,
    val allowedPackages: MutableSet<String>,
    val isBuiltIn: Boolean = false
)

class AppPreferences(private val context: Context) {

    private val prefs = context.getSharedPreferences("streamlimit_mobile_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_PC_TETHERING_MODE = "pc_tethering_mode"
        private const val KEY_PROTECT_TETHERING = "protect_tethering"
        private const val KEY_PROTECT_DNS_CORE = "protect_dns_core"
        private const val KEY_PROTECT_GMS = "protect_gms"
        private const val KEY_PROFILES_JSON = "profiles_json_v1"

        const val PROFILE_PC_ONLY = "pc_only"
        const val PROFILE_GAMING = "gaming"
        const val PROFILE_STREAM_SOCIAL = "stream_social"
        const val PROFILE_CUSTOM = "custom"

        val TETHERING_SYSTEM_CANDIDATES = listOf(
            "com.android.networkstack.tethering",
            "com.google.android.networkstack.tethering",
            "com.android.tethering",
            "com.samsung.android.networkstack.tethering",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.samsung.android.networkstack",
            "com.android.server.telecom",
            "com.sec.android.app.DataCreate"
        )

        val CORE_DNS_SYSTEM_CANDIDATES = listOf(
            "com.android.providers.settings",
            "com.android.captiveportallogin",
            "com.google.android.captiveportallogin",
            "com.android.networkstack.permissionconfig",
            "com.google.android.networkstack.permissionconfig"
        )

        val GMS_SYSTEM_CANDIDATES = listOf(
            "com.google.android.gms",
            "com.google.android.gsf"
        )
    }

    var activeProfileId: String
        get() = prefs.getString(KEY_ACTIVE_PROFILE_ID, PROFILE_GAMING) ?: PROFILE_GAMING
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, value).apply()

    var isPcTetheringMode: Boolean
        get() = prefs.getBoolean(KEY_PC_TETHERING_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PC_TETHERING_MODE, value).apply()

    var protectTetheringPackages: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_TETHERING, true)
        set(value) = prefs.edit().putBoolean(KEY_PROTECT_TETHERING, value).apply()

    var protectCoreDnsPackages: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_DNS_CORE, true)
        set(value) = prefs.edit().putBoolean(KEY_PROTECT_DNS_CORE, value).apply()

    var protectGmsPackages: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_GMS, false)
        set(value) = prefs.edit().putBoolean(KEY_PROTECT_GMS, value).apply()

    fun getProfiles(): MutableList<ProfileModel> {
        val raw = prefs.getString(KEY_PROFILES_JSON, null)
        if (raw.isNullOrBlank()) {
            val defaults = createDefaultProfiles()
            saveProfiles(defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ProfileModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkgArr = obj.optJSONArray("allowedPackages") ?: JSONArray()
                val pkgs = mutableSetOf<String>()
                for (j in 0 until pkgArr.length()) {
                    pkgs.add(pkgArr.getString(j))
                }
                list.add(
                    ProfileModel(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        iconEmoji = obj.optString("iconEmoji", "🛡️"),
                        isPcTetheringOnly = obj.optBoolean("isPcTetheringOnly", false),
                        allowedPackages = pkgs,
                        isBuiltIn = obj.optBoolean("isBuiltIn", false)
                    )
                )
            }
            if (list.isEmpty()) createDefaultProfiles() else list
        } catch (e: Exception) {
            createDefaultProfiles()
        }
    }

    fun saveProfiles(profiles: List<ProfileModel>) {
        val arr = JSONArray()
        for (p in profiles) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("description", p.description)
            obj.put("iconEmoji", p.iconEmoji)
            obj.put("isPcTetheringOnly", p.isPcTetheringOnly)
            obj.put("isBuiltIn", p.isBuiltIn)
            val pkgArr = JSONArray()
            p.allowedPackages.forEach { pkgArr.put(it) }
            obj.put("allowedPackages", pkgArr)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES_JSON, arr.toString()).apply()
    }

    fun getActiveProfile(): ProfileModel {
        val profiles = getProfiles()
        if (isPcTetheringMode) {
            return profiles.find { it.id == PROFILE_PC_ONLY } ?: profiles.first()
        }
        return profiles.find { it.id == activeProfileId } ?: profiles.first()
    }

    fun togglePackageInActiveProfile(packageName: String): Boolean {
        val profiles = getProfiles()
        val targetId = if (isPcTetheringMode) PROFILE_CUSTOM else activeProfileId
        if (isPcTetheringMode) {
            isPcTetheringMode = false
            activeProfileId = PROFILE_CUSTOM
        }
        val profile = profiles.find { it.id == targetId } ?: profiles.first()
        val added = if (profile.allowedPackages.contains(packageName)) {
            profile.allowedPackages.remove(packageName)
            false
        } else {
            profile.allowedPackages.add(packageName)
            true
        }
        saveProfiles(profiles)
        return added
    }

    fun setPackagesForActiveProfile(packages: Set<String>) {
        val profiles = getProfiles()
        val profile = profiles.find { it.id == activeProfileId } ?: profiles.first()
        profile.allowedPackages.clear()
        profile.allowedPackages.addAll(packages)
        saveProfiles(profiles)
    }

    fun addCustomProfile(name: String, description: String, copyCurrentWhitelist: Boolean): ProfileModel {
        val profiles = getProfiles()
        val currentAllowed = if (copyCurrentWhitelist) {
            getActiveProfile().allowedPackages.toMutableSet()
        } else {
            mutableSetOf()
        }
        val newProfile = ProfileModel(
            id = "custom_" + System.currentTimeMillis(),
            name = name,
            description = description.ifBlank { "Kullanıcı tanımlı özel izolasyon profili" },
            iconEmoji = "⚡",
            isPcTetheringOnly = false,
            allowedPackages = currentAllowed,
            isBuiltIn = false
        )
        profiles.add(newProfile)
        saveProfiles(profiles)
        activeProfileId = newProfile.id
        isPcTetheringMode = false
        return newProfile
    }

    fun deleteProfile(profileId: String): Boolean {
        val profiles = getProfiles()
        val target = profiles.find { it.id == profileId } ?: return false
        if (target.isBuiltIn) return false
        profiles.remove(target)
        saveProfiles(profiles)
        if (activeProfileId == profileId) {
            activeProfileId = PROFILE_GAMING
        }
        return true
    }

    /**
     * Detects which system tethering / hotspot / core packages actually exist on this device.
     */
    fun getInstalledSystemBypassPackages(forPcTetheringMode: Boolean): Set<String> {
        val result = mutableSetOf<String>()
        val pm = context.packageManager

        fun addIfInstalled(candidates: List<String>) {
            for (pkg in candidates) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    result.add(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }

        if (forPcTetheringMode || protectTetheringPackages) {
            addIfInstalled(TETHERING_SYSTEM_CANDIDATES)
        }
        if (protectCoreDnsPackages) {
            addIfInstalled(CORE_DNS_SYSTEM_CANDIDATES)
        }
        if (!forPcTetheringMode && protectGmsPackages) {
            addIfInstalled(GMS_SYSTEM_CANDIDATES)
        }
        return result
    }

    /**
     * Computes the final set of packages to pass to VpnService.Builder.addDisallowedApplication().
     * In PC Tethering Mode, 0 user apps are allowed — only tethering/core system packages bypass the sinkhole.
     */
    fun getEffectiveBypassPackages(): Set<String> {
        val pcMode = isPcTetheringMode || activeProfileId == PROFILE_PC_ONLY
        val bypassSet = mutableSetOf<String>()
        bypassSet.addAll(getInstalledSystemBypassPackages(forPcTetheringMode = pcMode))
        if (!pcMode) {
            val active = getActiveProfile()
            bypassSet.addAll(active.allowedPackages)
        }
        return bypassSet
    }

    private fun createDefaultProfiles(): MutableList<ProfileModel> {
        val pm = context.packageManager
        val installed = try {
            pm.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val gamingCandidates = listOf(
            "com.discord",
            "com.tencent.ig",
            "com.pubg.imobile",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.teamfighttactics",
            "com.mobile.legends",
            "com.supercell.brawlstars",
            "com.supercell.clashroyale",
            "com.activision.callofduty.shooter",
            "com.miHoYo.GenshinImpact",
            "com.roblox.client",
            "com.valvesoftware.android.steam.community"
        ).filter { installed.contains(it) }.toMutableSet()

        val socialCandidates = listOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.discord",
            "com.google.android.youtube",
            "tv.twitch.android.app",
            "com.spotify.music",
            "com.instagram.android",
            "com.twitter.android",
            "com.android.chrome"
        ).filter { installed.contains(it) }.toMutableSet()

        return mutableListOf(
            ProfileModel(
                id = PROFILE_PC_ONLY,
                name = "Sadece PC (Tethering)",
                description = "Telefondaki TÜM uygulamaların internetini keser. Sadece USB Tethering ve Wi-Fi Hotspot paketlerini korur; tüm hızı %100 PC'ye aktarır.",
                iconEmoji = "💻",
                isPcTetheringOnly = true,
                allowedPackages = mutableSetOf(),
                isBuiltIn = true
            ),
            ProfileModel(
                id = PROFILE_GAMING,
                name = "Oyun Modu",
                description = "Arka plan güncellemelerini ve telemetriyi tamamen susturur. Sadece seçili mobil oyunlara ve sesli sohbete 0 ms ek gecikmeyle saf internet verir.",
                iconEmoji = "🎮",
                isPcTetheringOnly = false,
                allowedPackages = gamingCandidates,
                isBuiltIn = true
            ),
            ProfileModel(
                id = PROFILE_STREAM_SOCIAL,
                name = "Sosyal / Yayın Modu",
                description = "Yayında, video izlerken veya mesajlaşırken diğer uygulamaların bant genişliği tüketmesini engeller.",
                iconEmoji = "📡",
                isPcTetheringOnly = false,
                allowedPackages = socialCandidates,
                isBuiltIn = true
            ),
            ProfileModel(
                id = PROFILE_CUSTOM,
                name = "Özel Profil",
                description = "Kendi seçtiğiniz beyaz liste (Whitelist) uygulamaları dışındaki her şeyi çekirdek düzeyinde izole eder.",
                iconEmoji = "🛡️",
                isPcTetheringOnly = false,
                allowedPackages = mutableSetOf(),
                isBuiltIn = true
            )
        )
    }
}
