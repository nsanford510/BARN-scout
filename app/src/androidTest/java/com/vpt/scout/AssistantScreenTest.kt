package com.vpt.scout

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vpt.scout.assistant.AssistantController
import com.vpt.scout.assistant.AssistantService
import com.vpt.scout.ui.screens.AssistantScreen
import com.vpt.scout.ui.theme.ScoutAppTheme
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestActivity>()

    @Test fun assistantControlsAndPropertyContext() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = app.getSharedPreferences("assistant-ui-test", Context.MODE_PRIVATE)
        val auth = SupabaseAuthManager(prefs, OkHttpClient(), "https://example.supabase.co", "public-placeholder")
        val controller = AssistantController(AssistantService(auth))
        try {
            compose.setContent { ScoutAppTheme { AssistantScreen(controller) } }
            compose.onNodeWithText("Live voice").assertIsDisplayed()
            compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
            compose.onNodeWithText("Research this owner").assertIsNotEnabled()
            compose.onNodeWithText("Property APN (optional)").performTextInput("001-011100100")
            compose.onNodeWithText("Research this owner").assertIsEnabled()
            compose.runOnIdle {
                (app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            }
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            // Public Downloads survives Gradle uninstalling the test app after the test run.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "BARN-assistant-screen.png")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
            val uri = checkNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            checkNotNull(app.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { compose.runOnIdle { controller.close() } }
    }
}
