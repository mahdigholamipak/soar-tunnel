package com.soar.tunnel

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Default Server",
    var serverKey: String = "",
    var domains: String = "",
    var port: Int = 10850,
    var resolvers: String = "",
    var isLocked: Boolean = false
) {
    override fun toString(): String {
        return if (isLocked) "🔒 $name (Protected)" else name
    }
}

object ProfileManager {
    private const val PREFS_NAME = "soar_profiles"
    private const val KEY_PROFILES_JSON = "profiles_list"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    val DEFAULT_RESOLVERS = """
        1.1.1.1:53
        1.0.0.1:53
        8.8.8.8:53
        8.8.4.4:53
        9.9.9.9:53
        149.112.112.112:53
        208.67.222.222:53
        208.67.220.220:53
        94.140.14.14:53
        4.2.2.4:53
        178.22.122.100:53
        185.51.200.2:53
        10.202.10.202:53
        10.202.10.102:53
        78.157.42.100:53
        78.157.42.101:53
        217.218.155.155:53
        217.218.127.127:53
        85.15.1.14:53
        185.212.51.144:53
        46.100.40.59:53
        5.202.171.124:53
        46.100.164.165:53
        93.114.104.185:53
        2.188.162.78:53
        87.107.82.213:53
        91.92.130.49:53
        81.29.248.38:53
        188.136.162.218:53
        185.83.114.233:53
        94.182.154.105:53
        178.252.143.133:53
        85.185.157.2:53
        79.127.125.126:53
        78.157.56.133:53
        80.191.68.247:53
        87.107.109.107:53
        87.107.184.123:53
        31.214.251.47:53
        31.214.251.58:53
        2.144.22.176:53
        2.144.20.37:53
        194.225.101.22:53
        94.183.149.147:53
        94.232.174.194:53
        5.200.200.200:53
        91.98.98.98:53
    """.trimIndent()

    fun loadProfiles(context: Context): MutableList<ServerProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PROFILES_JSON, null)
        val list = mutableListOf<ServerProfile>()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ServerProfile(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            name = obj.optString("name", "Server"),
                            serverKey = obj.optString("serverKey", ""),
                            domains = obj.optString("domains", ""),
                            port = obj.optInt("port", 10850),
                            resolvers = obj.optString("resolvers", DEFAULT_RESOLVERS),
                            isLocked = obj.optBoolean("isLocked", false)
                        )
                    )
                }
            } catch (e: Exception) { }
        }

        if (list.isEmpty()) {
            // Migrate legacy single settings
            val legacyPrefs = context.getSharedPreferences("soar_prefs", Context.MODE_PRIVATE)
            val legacyKey = legacyPrefs.getString("server_key", "") ?: ""
            val legacyDomains = legacyPrefs.getString("domains", "") ?: ""
            val legacyPort = legacyPrefs.getInt("port", 10850)
            val legacyResolvers = legacyPrefs.getString("resolvers", DEFAULT_RESOLVERS) ?: DEFAULT_RESOLVERS

            val initial = ServerProfile(
                name = "Default Server",
                serverKey = legacyKey,
                domains = legacyDomains,
                port = legacyPort,
                resolvers = legacyResolvers,
                isLocked = false
            )
            list.add(initial)
            saveProfiles(context, list)
        }

        return list
    }

    fun saveProfiles(context: Context, profiles: List<ServerProfile>) {
        val array = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("serverKey", p.serverKey)
                put("domains", p.domains)
                put("port", p.port)
                put("resolvers", p.resolvers)
                put("isLocked", p.isLocked)
            }
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PROFILES_JSON, array.toString()).apply()
    }

    fun getActiveProfileId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROFILE_ID, null)
    }

    fun setActiveProfileId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    fun exportProfileUri(profile: ServerProfile, locked: Boolean): String {
        val nameEncoded = Uri.encode(profile.name)
        return if (locked || profile.isLocked) {
            val domainArray = JSONArray()
            profile.domains.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                domainArray.put(it)
            }
            val payload = JSONObject().apply {
                put("name", profile.name)
                put("domains", domainArray)
                put("key", profile.serverKey)
                put("port", profile.port)
                put("locked", true)
            }
            val b64 = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "soar://locked?name=$nameEncoded&data=${Uri.encode(b64)}"
        } else {
            val domainsEncoded = Uri.encode(profile.domains)
            val keyEncoded = Uri.encode(profile.serverKey)
            "soar://profile?name=$nameEncoded&domains=$domainsEncoded&key=$keyEncoded&port=${profile.port}"
        }
    }

    fun importProfileUri(uriString: String): ServerProfile? {
        if (uriString.isBlank()) return null
        val trimmed = uriString.trim()

        try {
            val uri = Uri.parse(trimmed)
            if (trimmed.startsWith("soar://profile", ignoreCase = true)) {
                val name = uri.getQueryParameter("name") ?: "Imported Server"
                val domains = uri.getQueryParameter("domains") ?: ""
                val key = uri.getQueryParameter("key") ?: ""
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 10850

                return ServerProfile(
                    name = name,
                    serverKey = key,
                    domains = domains,
                    port = port,
                    resolvers = DEFAULT_RESOLVERS,
                    isLocked = false
                )
            } else if (trimmed.startsWith("soar://locked", ignoreCase = true)) {
                val name = uri.getQueryParameter("name") ?: "Protected Server"
                val data = uri.getQueryParameter("data") ?: ""
                if (data.isBlank()) return null

                val jsonBytes = Base64.decode(data, Base64.DEFAULT)
                val jsonStr = String(jsonBytes, Charsets.UTF_8)
                val obj = JSONObject(jsonStr)

                val parsedName = obj.optString("name", name)
                val key = obj.optString("key", "")
                val port = obj.optInt("port", 10850)
                val domainsArray = obj.optJSONArray("domains")
                val domainList = mutableListOf<String>()
                if (domainsArray != null) {
                    for (i in 0 until domainsArray.length()) {
                        domainList.add(domainsArray.getString(i))
                    }
                }

                return ServerProfile(
                    name = parsedName,
                    serverKey = key,
                    domains = domainList.joinToString(", "),
                    port = port,
                    resolvers = DEFAULT_RESOLVERS,
                    isLocked = true
                )
            }
        } catch (e: Exception) { }

        return null
    }
}
