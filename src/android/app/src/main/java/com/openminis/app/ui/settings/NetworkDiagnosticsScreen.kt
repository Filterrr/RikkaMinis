package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.network.NetworkProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [feat1-network-diagnostics] Network Diagnostics panel.
 *
 * Lists every configured provider origin and lets the user run a staged
 * probe (DNS → TCP → TLS → first byte) against each, showing per-leg
 * timings so "slow" gets a location. Auto-probes once on entry (read-only
 * HEAD requests, no credentials); a per-row Re-test button re-runs one
 * origin; "Probe all" re-runs everything.
 */
@Composable
fun NetworkDiagnosticsScreen(
    providerBaseUrls: List<String>,
    onBack: () -> Unit,
) {
    // Keyed by origin → latest probe result (null = not probed yet).
    var results by remember { mutableStateOf<Map<String, NetworkProbe.ProbeResult?>>(emptyMap()) }
    var running by remember { mutableStateOf<String?>(null) }

    val origins = remember(providerBaseUrls) {
        providerBaseUrls.map { NetworkProbe.normalizeKey(it) }.distinct()
    }

    suspend fun runProbe(origin: String) {
        running = origin
        val r = withContext(Dispatchers.IO) { NetworkProbe.probe(origin) }
        results = results + (origin to r)
        running = null
    }

    // Auto-probe once per entry, sequentially (parallel probes would skew
    // each other's timings on a constrained radio).
    LaunchedEffect(origins) {
        if (results.isEmpty() && origins.isNotEmpty()) {
            origins.forEach { origin -> runProbe(origin) }
        }
    }

    SettingsScaffold(title = stringResource(R.string.network_diag_title), onBack = onBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.network_diag_footer),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                // Re-run everything; results reset so rows show "probing".
                results = origins.associateWith { null }
            }) {
                Text(stringResource(R.string.network_diag_probe_all))
            }
        }

        if (origins.isEmpty()) {
            Text(
                text = stringResource(R.string.network_diag_empty),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            )
            return@SettingsScaffold
        }

        origins.forEach { origin ->
            val result = results[origin]
            val isRunning = running == origin || (results[origin] == null && running != null)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = origin,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (isRunning) {
                        Text(
                            text = stringResource(R.string.network_diag_probing),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TextButton(onClick = { results = results + (origin to null) }) {
                            Text(stringResource(R.string.network_diag_retest))
                        }
                    }
                }
                if (result != null) {
                    Text(
                        text = stringResource(
                            R.string.network_diag_legs,
                            fmt(result.dnsMs), fmt(result.tcpMs), fmt(result.tlsMs), fmt(result.ttfbMs),
                        ),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = result.verdict(),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (result.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** "12ms" or "—" for a missing leg. */
private fun fmt(ms: Long?): String = if (ms == null) "—" else "${ms}ms"
