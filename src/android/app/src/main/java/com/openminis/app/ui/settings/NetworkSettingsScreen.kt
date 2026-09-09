package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.network.NetworkSettings
import com.openminis.app.ui.components.SectionTextField

/**
 * [OPT-doh / OPT-proxy / OPT-pool-capacity] Network settings screen.
 *
 *  - DoH: shared resolver for every LLM client. Toggling applies on the
 *    next lookup (buildDns reads the current resolver at lookup time);
 *    malformed URLs are stored but inert — dohTemplateUrl() returns null
 *    and clients keep the system DNS.
 *  - App-level HTTP proxy: fallback for instances without a per-instance
 *    override. Applied to NEW client builds (route change rebuilds) and to
 *    connection warmups; per-instance wins when both are set.
 *  - Shared-pool idle capacity: replaces the pool object immediately;
 *    existing clients migrate to the new pool on their next rebuild.
 *
 * All writes are immediate (SharedPreferences + in-memory @Volatile), no
 * save button — matching the provider connection screen's on-blur save.
 */
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var dohEnabled by rememberSaveable { mutableStateOf(NetworkSettings.dohEnabled) }
    var dohUrl by rememberSaveable { mutableStateOf(NetworkSettings.dohUrl) }
    var proxyUrl by rememberSaveable { mutableStateOf(NetworkSettings.proxyUrl) }
    var poolIdle by rememberSaveable { mutableStateOf(NetworkSettings.llmMaxIdleConnections.toString()) }

    SettingsScaffold(
        title = stringResource(R.string.settings_network_title),
        onBack = onBack,
    ) {
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
                                        NetworkSettings.setDoh(context, dohEnabled, dohUrl)
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

        // ─── App-level proxy ────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.settings_network_proxy_header),
            footer = stringResource(R.string.settings_network_proxy_footer),
        ) {
            SettingsCardBlock {
                // [OPT-webview-proxy] Route WebView-based browsing through
                // the proxy below too. Off = WebView follows the system
                // proxy (original behaviour). Requires a parseable proxy
                // URL to take effect; state is surfaced in the footer text.
                var webviewProxyOn by rememberSaveable { mutableStateOf(NetworkSettings.webviewProxyEnabled) }
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_network_webview_proxy_toggle),
                    subtitle = stringResource(R.string.settings_network_webview_proxy_subtitle),
                    checked = webviewProxyOn,
                    onCheckedChange = { enabled ->
                        webviewProxyOn = enabled
                        NetworkSettings.setWebviewProxyEnabled(context, enabled)
                        AppLogger.info(TAG, "WebView proxy ${if (enabled) "enabled" else "disabled"}")
                    },
                    showDivider = true,
                )
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_network_proxy_url),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    SectionTextField(
                        value = proxyUrl,
                        onValueChange = { proxyUrl = it },
                        singleLine = true,
                        placeholder = "http://127.0.0.1:7890",
                        fieldModifier = Modifier
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    NetworkSettings.setProxy(context, proxyUrl)
                                }
                            },
                    )
                    if (proxyUrl.isNotBlank() &&
                        NetworkSettings.parseProxyUrl(proxyUrl) == null
                    ) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.provider_detail_proxy_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ─── Connection pool ────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.settings_network_pool_header),
            footer = stringResource(R.string.settings_network_pool_footer),
        ) {
            SettingsCardBlock {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_network_pool_idle),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    SectionTextField(
                        value = poolIdle,
                        onValueChange = { poolIdle = it },
                        singleLine = true,
                        placeholder = NetworkSettings.DEFAULT_POOL_IDLE.toString(),
                        fieldModifier = Modifier
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    NetworkSettings.sanitizePoolIdle(poolIdle)?.let { v ->
                                        poolIdle = v.toString()
                                        NetworkSettings.setPoolIdle(context, v)
                                        AppLogger.info(TAG, "Pool idle capacity set to $v")
                                    }
                                    // Unparsable input: field snaps back on
                                    // next recomposition from the store.
                                    poolIdle = NetworkSettings.llmMaxIdleConnections.toString()
                                }
                            },
                    )
                }
            }
        }
    }
}

private const val TAG = "NetworkSettings"
