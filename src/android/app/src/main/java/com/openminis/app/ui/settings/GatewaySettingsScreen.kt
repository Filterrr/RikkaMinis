package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.gateway.GatewayClientRecipes
import com.openminis.app.gateway.GatewayServer
import com.openminis.app.gateway.GatewaySettings
import com.openminis.app.sandbox.ExecutionCoordinator

/**
 * [T-local-llm-gateway] Settings page for the local LLM gateway.
 *
 * Surface contract: every knob that widens reachability shows its security
 * consequence next to it. Enabling LAN access silently forces a token (see
 * [GatewaySettings.update]) — the section footer says so instead of leaving the
 * user to diff behaviour.
 *
 * Status is polled while the screen is open: the listener is a background thread
 * that can die on its own (port conflict), and a stale "running" badge on a
 * feature that spends the user's API budget is worse than a one-second flicker.
 */
@Composable
fun GatewaySettingsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(GatewaySettings.snapshot()) }
    var status by remember { mutableStateOf(GatewayServer.status(context)) }
    var portText by rememberSaveable { mutableStateOf(GatewaySettings.port.toString()) }
    var tokenDraft by rememberSaveable { mutableStateOf(GatewaySettings.token) }
    var allowlistDraft by remember { mutableStateOf(GatewaySettings.modelAllowlist.joinToString("\n")) }
    var recipeOpen by rememberSaveable { mutableStateOf(false) }
    var catalogue by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(snapshot.enabled) {
        while (true) {
            status = withContext(Dispatchers.IO) { GatewayServer.status(context) }
            delay(if (snapshot.enabled) 1_000L else 3_000L)
        }
    }

    LaunchedEffect(Unit) {
        catalogue = withContext(Dispatchers.IO) {
            runCatching {
                providerRepository.resolvedAgentLoopEntries().map { it.model.id }.distinct()
            }.getOrDefault(emptyList())
        }
    }

    /** Persist, restart the listener if the socket config moved, refresh shells. */
    fun persist(
        enabled: Boolean = snapshot.enabled,
        port: Int = snapshot.port,
        bindLan: Boolean = snapshot.bindLan,
        token: String = snapshot.token,
        defaultModel: String? = snapshot.defaultModel,
        allowlist: List<String> = snapshot.modelAllowlist,
    ) {
        val previous = snapshot
        val next = GatewaySettings.update(
            context = context,
            enabled = enabled,
            port = port,
            bindLan = bindLan,
            token = token,
            defaultModel = defaultModel,
            modelAllowlist = allowlist,
        )
        snapshot = next
        portText = next.port.toString()
        // A token-only edit needs no restart (GatewayAuth re-reads per request);
        // enable / port / bind-scope changes own the socket and must.
        if (previous.enabled != next.enabled || previous.port != next.port || previous.bindLan != next.bindLan) {
            scope.launch(Dispatchers.IO) {
                GatewayServer.syncFromSettings(context.applicationContext, providerRepository)
                status = GatewayServer.status(context)
            }
        }
        scope.launch { ExecutionCoordinator.broadcastGatewayEnvChange() }
    }

    SettingsScaffold(title = stringResource(R.string.gateway_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.gateway_section_service),
            footer = stringResource(R.string.gateway_section_service_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.gateway_enable),
                subtitle = stringResource(R.string.gateway_enable_subtitle),
                checked = snapshot.enabled,
                onCheckedChange = { on -> persist(enabled = on) },
                icon = Icons.Outlined.Router,
            )
            SettingsRow(
                title = stringResource(R.string.gateway_status),
                subtitle = when {
                    status.running -> stringResource(R.string.gateway_status_running, status.host, status.port)
                    snapshot.enabled -> stringResource(R.string.gateway_status_failed, status.error ?: "-")
                    else -> stringResource(R.string.gateway_status_stopped)
                },
                icon = Icons.Outlined.Memory,
                showChevron = false,
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { raw -> portText = raw.filter { it.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.gateway_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                isError = portText.trim().toIntOrNull() !in 1..65535,
                supportingText = { Text(stringResource(R.string.gateway_port_footer)) },
                trailingIcon = {
                    IconButton(
                        enabled = portText.trim().toIntOrNull() in 1..65535 &&
                            portText.trim().toIntOrNull() != snapshot.port,
                        onClick = { portText.trim().toIntOrNull()?.let { persist(port = it) } },
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.gateway_port_apply))
                    }
                },
            )
        }

        SettingsSection(
            header = stringResource(R.string.gateway_section_reach),
            footer = stringResource(
                if (snapshot.bindLan) R.string.gateway_lan_footer_on else R.string.gateway_lan_footer_off,
            ),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.gateway_lan),
                subtitle = stringResource(R.string.gateway_lan_subtitle),
                checked = snapshot.bindLan,
                onCheckedChange = { on -> persist(bindLan = on) },
                icon = Icons.Outlined.PhoneAndroid,
            )
            if (snapshot.bindLan) {
                SettingsRow(
                    title = stringResource(R.string.gateway_lan_address),
                    subtitle = GatewayServer.lanAddress()
                        ?: stringResource(R.string.gateway_lan_address_none),
                    icon = Icons.Outlined.Router,
                    showChevron = false,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.gateway_section_token),
            footer = stringResource(
                if (GatewaySettings.tokenAutoGenerated()) R.string.gateway_token_footer_auto
                else R.string.gateway_token_footer,
            ),
        ) {
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it.trim() },
                label = { Text(stringResource(R.string.gateway_token_label)) },
                placeholder = { Text(stringResource(R.string.gateway_token_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                supportingText = {
                    Text(
                        if (tokenDraft.isBlank()) stringResource(R.string.gateway_token_using_auto)
                        else stringResource(R.string.gateway_token_saved),
                    )
                },
                trailingIcon = {
                    IconButton(onClick = {
                        val effective = GatewaySettings.currentToken()
                        if (effective.isNotBlank()) clipboard.setText(AnnotatedString(effective))
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.gateway_token_copy))
                    }
                },
            )
            SettingsRow(
                title = stringResource(R.string.gateway_token_apply),
                subtitle = stringResource(R.string.gateway_token_apply_subtitle),
                onClick = {
                    persist(token = tokenDraft)
                    tokenDraft = GatewaySettings.token
                },
                icon = Icons.Outlined.Key,
            )
        }

        SettingsSection(
            header = stringResource(R.string.gateway_section_models),
            footer = stringResource(R.string.gateway_section_models_footer, catalogue.size),
        ) {
            SettingsValueRow(
                title = stringResource(R.string.gateway_default_model),
                value = snapshot.defaultModel ?: stringResource(R.string.gateway_default_model_none),
                subtitle = stringResource(R.string.gateway_default_model_subtitle),
                onClick = {
                    // Tap cycles the catalogue: a picker sheet is overkill for a
                    // value that is almost always the entry the user chats with.
                    val pool = catalogue
                    if (pool.isNotEmpty()) {
                        val idx = pool.indexOf(snapshot.defaultModel)
                        persist(defaultModel = pool[(idx + 1).mod(pool.size)])
                    }
                },
            )
            OutlinedTextField(
                value = allowlistDraft,
                onValueChange = { allowlistDraft = it },
                label = { Text(stringResource(R.string.gateway_allowlist_label)) },
                placeholder = { Text(catalogue.take(4).joinToString("\n")) },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                supportingText = { Text(stringResource(R.string.gateway_allowlist_footer)) },
            )
            SettingsRow(
                title = stringResource(R.string.gateway_allowlist_apply),
                subtitle = stringResource(R.string.gateway_allowlist_apply_subtitle, snapshot.modelAllowlist.size),
                onClick = {
                    persist(
                        allowlist = allowlistDraft.lineSequence()
                            .map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                    )
                },
            )
        }

        SettingsSection(
            header = stringResource(R.string.gateway_section_clients),
            footer = stringResource(R.string.gateway_section_clients_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.gateway_recipes_show),
                subtitle = stringResource(R.string.gateway_recipes_show_subtitle),
                checked = recipeOpen,
                onCheckedChange = { recipeOpen = it },
                icon = Icons.Outlined.ContentCopy,
            )
        }

        if (recipeOpen) {
            val host = status.host
            val token = GatewaySettings.currentToken()
            val model = snapshot.defaultModel ?: catalogue.firstOrNull() ?: "minis"
            val env = GatewayClientRecipes.envBlock(host, status.port, token)
            val full = GatewayClientRecipes.render(host, status.port, token, model)
            SettingsCardBlock {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        env,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(env)) }) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.gateway_recipes_copy),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { clipboard.setText(AnnotatedString(full)) }) {
                            Icon(
                                Icons.Outlined.Router,
                                contentDescription = stringResource(R.string.gateway_recipes_copy_full),
                            )
                        }
                    }
                }
            }
        }
    }
}
