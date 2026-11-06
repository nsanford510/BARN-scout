package com.vpt.scout

import android.content.Context
import android.graphics.Bitmap
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
import java.io.File

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
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val file = File(app.getExternalFilesDir(null), "assistant-screen.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { compose.runOnIdle { controller.close() } }
    }
}
