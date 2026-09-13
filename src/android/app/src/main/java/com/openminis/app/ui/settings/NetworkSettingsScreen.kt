package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkSettings
import com.openminis.app.ui.components.SectionTextField

/**
 * [OPT-restore-doh] Network settings screen — DoH only.
 *
 * (The former proxy sections and pool-capacity knob were removed with the
 * proxy system revert 2867abc and stay removed.)
 *
 * Toggling applies on the next lookup (buildDns reads the current resolver
 * at lookup time); malformed URLs are stored but inert — dohTemplateUrl()
 * returns null and clients keep the system DNS.
 */
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
    // [feat1-network-diagnostics] Entry into the staged per-origin probe panel.
    onDiagnosticsClick: () -> Unit = {},
) {
    val context = LocalContext.current

    var dohEnabled by rememberSaveable { mutableStateOf(NetworkSettings.dohEnabled) }
    var dohUrl by rememberSaveable { mutableStateOf(NetworkSettings.dohUrl) }
    // [feat4-doh-presets] Per-preset DoH latency (null = untested), plus the
    // running flag that drives one measurement round across all presets.
    var testLatencyMs by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var testRunning by remember { mutableStateOf(false) }

    // [feat4-doh-presets] One round = RFC 8484 GET dns-query?dns=<base64url
    // wire-format query> against each preset, timed. Runs while visible;
    // re-triggered by the Test button. ~40 byte query for example.org A.
    LaunchedEffect(testRunning) {
        if (!testRunning) return@LaunchedEffect
        testLatencyMs = kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.openminis.app.network.DoHBootstrap.PRESETS.associate { preset ->
                preset.id to com.openminis.app.network.DoHTester.probeLatencyMs(preset.url)
            }
        }
        testRunning = false
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_network_title),
        onBack = onBack,
    ) {
        // ─── Network Diagnostics entry ──────────────────────────────
        // [feat1-network-diagnostics] Staged per-origin probe panel.
        SettingsSection(
            header = stringResource(R.string.network_diag_title),
            footer = stringResource(R.string.network_diag_footer),
        ) {
            SettingsCardBlock {
                SettingsRow(
                    title = stringResource(R.string.network_diag_entry_title),
                    subtitle = stringResource(R.string.network_diag_entry_subtitle),
                    onClick = onDiagnosticsClick,
                )
            }
        }

        // ─── DNS over HTTPS ─────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.settings_network_doh_header),
            footer = stringResource(R.string.settings_network_doh_footer),
        ) {
            SettingsCardBlock {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_network_doh_toggle),
                    subtitle = stringResource(R.string.settings_network_doh_toggle_subtitle),
                    checked = dohEnabled,
                    onCheckedChange = { enabled ->
                        dohEnabled = enabled
                        NetworkSettings.setDoh(context, enabled, dohUrl)
                        AppLogger.info(TAG, "DoH ${if (enabled) "enabled" else "disabled"} url=$dohUrl")
                    },
                    showDivider = dohEnabled,
                )
                if (dohEnabled) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        // [feat4-doh-presets] One-tap presets for the well-known
                        // public resolvers (each carries pinned bootstrap IPs —
                        // see DoHBootstrap) plus a live connectivity test.
                        Text(
                            text = stringResource(R.string.settings_network_doh_presets),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        com.openminis.app.network.DoHBootstrap.PRESETS.forEach { preset ->
                            val selected = dohUrl.equals(preset.url, ignoreCase = true)
                            val latency: Long? = testLatencyMs[preset.id]
                            SettingsRow(
                                title = preset.label,
                                subtitle = preset.url,
                                showChevron = false,
                                showDivider = true,
                                trailing = {
                                    Text(
                                        text = if (latency == null) "" else "${latency}ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (latency != null && latency < 300)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    dohUrl = preset.url
                                    NetworkSettings.setDoh(context, dohEnabled, preset.url)
                                    AppLogger.info(TAG, "DoH preset selected: ${preset.id}")
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                testRunning = true
                                testLatencyMs = emptyMap()
                            }) {
                                Text(stringResource(R.string.settings_network_doh_test))
                            }
                            if (testRunning) {
                                Spacer(Modifier.width(12.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.settings_network_doh_url),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        SectionTextField(
                            value = dohUrl,
                            onValueChange = { dohUrl = it },
                            singleLine = true,
                            placeholder = "https://dns.alidns.com/dns-query",
                            fieldModifier = Modifier
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        // FIX(pr32-bug3): blanking the field used
                                        // to commit the empty string, which
                                        // setDoh() "helpfully" resets to the
                                        // DEFAULT DoH URL — silently wiping the
                                        // user's stored custom endpoint. Treat a
                                        // blank field as "not edited": snap back
                                        // to the stored value instead of
                                        // committing. Non-blank edits commit as
                                        // before (setDoh still validates).
                                        if (dohUrl.isBlank()) {
                                            dohUrl = NetworkSettings.dohUrl
                                            AppLogger.info(TAG, "DoH url blank on blur — kept stored value")
                                        } else {
                                            NetworkSettings.setDoh(context, dohEnabled, dohUrl)
                                        }
                                    }
                                },
                        )
                        // Malformed / non-https URL: stored but inert —
                        // dohTemplateUrl() returns null and every client
                        // keeps the system DNS until it's fixed.
                        if (NetworkSettings.dohTemplateUrl() == null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.settings_network_doh_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val TAG = "NetworkSettings"
