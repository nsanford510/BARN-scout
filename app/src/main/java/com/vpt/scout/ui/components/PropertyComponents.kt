package com.vpt.scout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.vpt.scout.Property
import com.vpt.scout.ui.openDirections
import com.vpt.scout.ui.propertyImageUrl

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PropertyBadges(property: Property, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (property.hasVpt) {
            Badge(containerColor = Color(0xFFE53935)) {
                Text("VPT", fontSize = 10.sp)
            }
        }
        if (property.isDelinquent) {
            Badge(containerColor = Color(0xFFAD1457)) {
                Text("Delinquent", fontSize = 10.sp)
            }
        }
        property.powerStatus?.takeIf { it.isNotBlank() }?.let { power ->
            Badge(containerColor = if (power.equals("off", ignoreCase = true)) Color(0xFFE67E22) else Color(0xFF2E7D32)) {
                Text(power.uppercase(), fontSize = 10.sp)
            }
        }
        property.conditionScore?.let { score ->
            val color = when {
                score >= 7f -> Color(0xFFE53935)
                score >= 4f -> Color(0xFFFFC107)
                else -> Color(0xFF4CAF50)
            }
            Badge(containerColor = color) {
                Text("${"%.0f".format(score)}/10", fontSize = 10.sp)
            }
        }
        when (property.aiVacancyVerdict) {
            "likely_vacant" -> Color(0xFFE53935) to "AI vacant"
            "likely_occupied" -> Color(0xFF2E7D32) to "AI occupied"
            else -> null
        }?.let { (color, label) ->
            val pct = property.aiVacancyConfidence?.let { " ${"%.0f".format(it * 100)}%" }.orEmpty()
            Badge(containerColor = color) {
                Text("$label$pct", fontSize = 10.sp)
            }
        }
        if (property.isOutOfState) {
            Badge(containerColor = Color(0xFF7B1FA2)) {
                Text("OOS", fontSize = 10.sp)
            }
        }
        if (property.isScouted) {
            Badge(containerColor = Color(0xFF546E7A)) {
                Text("Scouted", fontSize = 10.sp)
            }
        }
    }
}

/** Stored property image, with a neutral placeholder while loading or on failure. */
@Composable
fun PropertyPhoto(property: Property, modifier: Modifier = Modifier) {
    val url = propertyImageUrl(property.streetviewImagePath)
    val placeholder: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.HomeWork,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
        }
    }
    Box(modifier = modifier) {
        if (url == null) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = property.address,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { placeholder() },
                error = { placeholder() }
            )
        }
    }
}

/**
 * Details for one property with a prominent Directions button. Shared by the
 * List, Gallery and Map tabs so tapping a property behaves the same everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertyDetailSheet(
    property: Property,
    onDismiss: () -> Unit,
    onAddToList: (() -> Unit)? = null,
    distanceText: String? = null
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
        ) {
            PropertyPhoto(
                property = property,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(modifier = Modifier.height(12.dp))

            distanceText?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.NearMe,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                property.address ?: property.apn,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                listOfNotNull(property.city, property.zipCode).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            PropertyBadges(property)
            Spacer(modifier = Modifier.height(12.dp))

            DetailLine("APN", property.apn)
            DetailLine("Owner", property.ownerName)
            DetailLine("Last payment", property.lastPayment)
            DetailLine("Tax year", property.taxYear)
            DetailLine("Research", property.researchStatus)
            DetailLine(
                "AI vacancy",
                property.aiVacancyVerdict?.replace('_', ' ')?.let { verdict ->
                    property.aiVacancyConfidence?.let { "$verdict (${"%.0f".format(it * 100)}%)" } ?: verdict
                }
            )
            DetailLine("Site vacant", property.siteVacant?.let { if (it) "Yes" else "No" })

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { openDirections(context, property) },
                enabled = property.latitude != null || !property.address.isNullOrBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Directions, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Directions", style = MaterialTheme.typography.titleMedium)
            }
            onAddToList?.let {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add to route list")
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
