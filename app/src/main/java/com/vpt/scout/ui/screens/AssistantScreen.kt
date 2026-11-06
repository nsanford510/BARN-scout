package com.vpt.scout.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vpt.scout.assistant.AssistantController
import com.vpt.scout.assistant.AssistantMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(controller: AssistantController) {
    val state by controller.state.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scroll = rememberLazyListState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.startLive() else controller.error("Microphone permission is needed for live voice. Typed chat still works.")
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.stop() }
    }
    LaunchedEffect(state.messages.size, state.userTranscript, state.assistantTranscript, state.busy) {
        if (scroll.layoutInfo.totalItemsCount > 0) scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Column { Text("Assistant"); Text("Gemini Live · BARN research", style = MaterialTheme.typography.labelSmall) } },
            actions = {
                IconButton(onClick = { controller.newConversation() }) { Icon(Icons.Default.AddComment, "New conversation") }
            }
        )
        OutlinedTextField(
            value = state.apn, onValueChange = controller::setApn,
            label = { Text("Property APN (optional)") }, singleLine = true,
            enabled = !state.busy && !state.live && !state.connecting,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        LazyColumn(
            state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.messages.isEmpty() && !state.live && !state.connecting) item {
                Text("Research a property with BARN data and public sources.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Enter an APN above, or ask about an address or owner. Start live voice to speak with Gemini.")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { controller.send("Find the owner and public contact sources for this property.") }, enabled = state.apn.isNotBlank() && !state.busy) {
                    Text("Research this owner")
                }
            }
            items(state.messages) { MessageCard(it) }
            if (state.userTranscript.isNotBlank()) item { MessageCard(AssistantMessage("user", state.userTranscript)) }
            if (state.assistantTranscript.isNotBlank()) item { MessageCard(AssistantMessage("assistant", state.assistantTranscript)) }
            if (state.busy || state.connecting || state.activity != null) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(state.activity ?: if (state.connecting) "Connecting to Gemini…" else "Researching…")
                }
            }
            state.error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                if (state.messages.lastOrNull()?.role == "user" && !state.live) TextButton(onClick = controller::retry, enabled = !state.busy) { Text("Retry") }
            } }
        }
        if (state.live) {
            Text("Tap Speak, then Finish when you’re done. Voice stops when you leave this screen.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = controller::toggleMicrophone, modifier = Modifier.weight(1f)) {
                    Icon(if (state.recording) Icons.Default.Stop else Icons.Default.Mic, null)
                    Spacer(Modifier.width(8.dp)); Text(if (state.recording) "Finish speaking" else "Speak")
                }
                OutlinedButton(onClick = controller::stop) { Text("End voice") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.startLive()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = !state.busy && !state.connecting) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Live voice")
                }
                if (state.busy || state.connecting) TextButton(onClick = controller::stop) { Text("Stop") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(4_000) },
                placeholder = { Text("Ask about a property…") }, maxLines = 4,
                enabled = !state.busy && !state.connecting,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { controller.send(draft); draft = "" }, enabled = draft.isNotBlank() && !state.busy && !state.connecting) {
                Icon(Icons.Default.Send, "Send message")
            }
        }
    }
}

@Composable
private fun MessageCard(message: AssistantMessage) {
    val uri = LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (message.role == "user") "You" else "BARN Assistant", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            SelectionContainer { Text(message.text) }
            message.steps.forEach { Text(it.summary, style = MaterialTheme.typography.bodySmall) }
            message.sources.distinct().filter { it.startsWith("https://") || it.startsWith("http://") }.forEach { url ->
                TextButton(onClick = { runCatching { uri.openUri(url) } }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(url, maxLines = 2)
                }
            }
        }
    }
}
