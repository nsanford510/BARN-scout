package com.vpt.scout

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackendConfigurationTest {
    @Test fun `only public Supabase key types are accepted`() {
        assertTrue(validPublishableKey("sb_publishable_abcdefghijklmnopqrstuvwxyz"))
        assertFalse(validPublishableKey("sb_secret_abcdefghijklmnopqrstuvwxyz"))
        assertFalse(validPublishableKey("YOUR_PUBLISHABLE_KEY"))
        assertFalse(validPublishableKey("AIza-not-a-supabase-key"))
        val anon = android.util.Base64.encodeToString("{\"role\":\"anon\"}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        val admin = android.util.Base64.encodeToString("{\"role\":\"service_role\"}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        assertTrue(validPublishableKey("header.$anon.signature"))
        assertFalse(validPublishableKey("header.$admin.signature"))
    }
}
