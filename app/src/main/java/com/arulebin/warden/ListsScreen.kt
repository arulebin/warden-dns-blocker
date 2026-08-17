package com.arulebin.warden

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ListsScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Lists", style = WordmarkStyle.copy(fontSize = 20.sp, letterSpacing = 4.sp))

        Panel {
            PanelHeader("BLOCKLIST") { Mono(Rules.userBlocked.size.toString(), Brand.textDim, 12) }
            AddField("example.com") { Rules.addBlocked(it) }
            if (Rules.userBlocked.isEmpty()) {
                EmptyHint("No custom domains blocked.")
            } else {
                Rules.userBlocked.forEach { d -> DomainRow(d) { Rules.removeBlocked(d) } }
            }
        }

        Panel {
            PanelHeader("ALLOWLIST") { Mono(Rules.userAllowed.size.toString(), Brand.textDim, 12) }
            EmptyHint("Always allowed — overrides the blocklist and every category.")
            AddField("example.com") { Rules.addAllowed(it) }
            Rules.userAllowed.forEach { d -> DomainRow(d) { Rules.removeAllowed(d) } }
        }

        Panel {
            PanelHeader("CATEGORIES")
            Lists.CATEGORIES.forEach { CategoryRow(it) }
        }

        Panel {
            val importedCount = Rules.count("imported")
            val busy = Lists.busy["imported"] == true
            PanelHeader("IMPORT A LIST") { Mono(importedCount.toString(), Brand.textDim, 12) }
            EmptyHint("Paste a hosts-format list URL to import thousands of domains at once.")
            AddField("https://…/hosts", buttonLabel = if (busy) "…" else "Import") {
                if (it.isNotBlank()) Lists.importFromUrl(it.trim())
            }
            Lists.lastError["imported"]?.let { Text("Error: $it", color = Brand.danger, fontSize = 12.sp) }
            if (importedCount > 0) {
                Text(
                    "Clear imported list",
                    color = Brand.shield,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { Lists.clearImported() }.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(cat: Lists.Category) {
    val enabled = Prefs.isCategoryEnabled(cat.key)
    val count = Rules.count("cat:${cat.key}")
    val busy = Lists.busy["cat:${cat.key}"] == true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(cat.title, color = Brand.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Switch(
                checked = enabled,
                onCheckedChange = { on -> if (on) Lists.enableCategory(cat.key) else Lists.disableCategory(cat.key) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Brand.ink,
                    checkedTrackColor = Brand.shield,
                    uncheckedThumbColor = Brand.textDim,
                    uncheckedTrackColor = Brand.surfaceHi,
                    uncheckedBorderColor = Brand.line,
                ),
            )
        }
        Text(cat.desc, color = Brand.textDim, fontSize = 12.sp, lineHeight = 16.sp)
        val status = when {
            busy -> "Downloading…"
            enabled && count > 0 -> "$count domains"
            enabled -> "Enabled"
            else -> ""
        }
        if (status.isNotEmpty()) {
            Mono(status, color = if (busy) Brand.alert else Brand.shield, size = 11)
        }
        Lists.lastError["cat:${cat.key}"]?.let { Text("Error: $it", color = Brand.danger, fontSize = 11.sp) }
    }
}
