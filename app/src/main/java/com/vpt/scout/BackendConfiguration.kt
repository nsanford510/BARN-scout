package com.vpt.scout

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser

/** Public app connection values only. Never accept a Gemini, service-role or secret key here. */
class BackendConfiguration(context: Context) {
    private val prefs = context.getSharedPreferences("backend_configuration", Context.MODE_PRIVATE)
    var configured by mutableStateOf(validPublishableKey(key))
        private set
    val url: String get() = prefs.getString("url", null) ?: BuildConfig.SUPABASE_URL
    val key: String get() = prefs.getString("key", null) ?: BuildConfig.SUPABASE_ANON_KEY
    fun save(url: String, key: String): Boolean {
        val cleanUrl = url.trim().trimEnd('/')
        if (!Regex("https://[a-z0-9-]+\\.supabase\\.co").matches(cleanUrl) || !validPublishableKey(key.trim())) return false
        prefs.edit().putString("url", cleanUrl).putString("key", key.trim()).apply()
        configured = true
        return true
    }
}

internal fun validPublishableKey(key: String): Boolean {
    if (key.startsWith("sb_publishable_") && key.length > 30) return true
    return runCatching {
        val body = String(Base64.decode(key.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        JsonParser().parse(body).asJsonObject.get("role")?.asString == "anon"
    }.getOrDefault(false)
}

@Composable
fun BackendSetupScreen(configuration: BackendConfiguration) {
    var url by remember { mutableStateOf(configuration.url.takeUnless { it.contains("YOUR_") } ?: "https://zezhsmgtdzlagqwlvjui.supabase.co") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Connect BARN", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("This preview needs your BARN Supabase publishable key. Find it in Supabase Settings → API Keys. Your Gemini key stays on the server.")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Supabase project URL") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Publishable / anon key") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        if (error) Text("Enter a Supabase URL and a publishable/anon key. Secret keys are not accepted.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { error = !configuration.save(url, key) }, modifier = Modifier.fillMaxWidth()) { Text("Continue to sign in") }
    }
}
