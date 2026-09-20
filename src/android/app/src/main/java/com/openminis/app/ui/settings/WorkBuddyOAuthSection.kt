package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.workbuddy.WorkBuddyConstants
import com.openminis.app.provider.workbuddy.WorkBuddyCredentialStore
import com.openminis.app.provider.workbuddy.WorkBuddyKeepAlive
import com.openminis.app.provider.workbuddy.WorkBuddyLoginManager
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-workbuddy-oauth] Sign-in section for WorkBuddy instances.
 *
 * Mirrors [AntigravityOAuthSection]'s shape (status line + primary action +
 * secondary actions) but the flow itself is different in one important way:
 * WorkBuddy's login is **poll-based** rather than redirect-based, so there is
 * no callback to wait on and no browser choice to honour — the backend hands
 * out a hosted URL and the client polls until the token appears. That removes
 * the loopback listener, the custom-scheme registration and the
 * `onActivityResult` plumbing the Antigravity flow needs.
 *
 * The region picker lives here as well as in the Add-Provider form: changing
 * tenants is a legitimate re-login scenario (e.g. the user signed into the
 * wrong one first), and doing it here keeps the correction close to the state
 * that reveals the mistake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkBuddyOAuthSection(
    instanceId: String,
    providerRepository: ProviderRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appContext = context.applicationContext

    val instance = providerRepository.config.value.instances.firstOrNull { it.id == instanceId }

    // Region: read from the instance so a change here survives recomposition
    // and is visible to the OAuth flow started below.
    var region by remember(instance?.workBuddyRegion) {
        mutableStateOf(WorkBuddyConstants.Region.from(instance?.workBuddyRegion))
    }

    // Credential state, refreshed after every login/logout.
    var tokens by remember { mutableStateOf(WorkBuddyCredentialStore.loadTokens(appContext, instanceId)) }
    var working by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // [Compose] Resource strings must be read in a composable context, but the
    // sign-in coroutine below runs outside one. Capture the copy it needs up
    // front so the lambda never touches the composition.
    val waitingText = stringResource(R.string.workbuddy_oauth_waiting)

    // [T-workbuddy-keepalive] UI mirrors of the keep-alive settings (the
    // machinery itself lives in WorkBuddyKeepAlive; these only read).
    var keepAliveEnabled by remember {
        mutableStateOf(WorkBuddyKeepAlive.isEnabled(appContext))
    }
    var keepAliveIntervalH by remember {
        mutableStateOf(WorkBuddyKeepAlive.intervalHours(appContext))
    }

    fun reload() {
        tokens = WorkBuddyCredentialStore.loadTokens(appContext, instanceId)
    }

    // [T-workbuddy-oauth] Paste-in credential import. WorkBuddy2API (and other
    // clients) export a session as JSON; accepting it lets a user who already
    // signed in elsewhere skip the browser round-trip entirely — the same
    // interop the Antigravity store offers via its upstream-shaped fields.
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    SettingsSection(
        header = stringResource(R.string.workbuddy_oauth_section_header),
        footer = stringResource(R.string.workbuddy_oauth_section_footer),
    ) {
        SettingsCardBlock {
            // ── Account status ──────────────────────────────────────────
            RowLabel(text = stringResource(R.string.workbuddy_region_label))
            Text(
                text = "${stringResource(R.string.workbuddy_account_prefix)}${
                    tokens?.displayName ?: "—"
                }",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (tokens != null) {
                Text(
                    text = "${stringResource(R.string.workbuddy_region_prefix)}${
                        WorkBuddyConstants.Region.from(tokens?.region).label
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // ── Region picker ──────────────────────────────────────────
            // Disabled while signed in: switching tenants invalidates the
            // stored session, so the user must sign out first. Enforcing it
            // in the UI is honest — silently keeping a token that belongs to
            // another tenant would produce confusing 401s.
            if (tokens == null) {
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    WorkBuddyConstants.Region.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = region == entry,
                            onClick = {
                                region = entry
                                // Persist immediately: the OAuth flow reads the
                                // instance, not this composable's state.
                                instance?.let {
                                    providerRepository.updateInstance(
                                        it.copy(workBuddyRegion = entry.id),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = WorkBuddyConstants.Region.entries.size,
                            ),
                        ) {
                            Text(
                                stringResource(
                                    if (entry == WorkBuddyConstants.Region.DOMESTIC) {
                                        R.string.workbuddy_region_domestic
                                    } else {
                                        R.string.workbuddy_region_international
                                    },
                                ),
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.workbuddy_region_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Primary action: sign in / sign in again ─────────────────
            MinisButton(
                onClick = {
                    val target = instance ?: return@MinisButton
                    working = true
                    statusMessage = waitingText
                    scope.launch {
                        val result = WorkBuddyLoginManager.login(
                            region = region,
                            openBrowser = { url ->
                                withContext(Dispatchers.Main) {
                                    WorkBuddyLoginManager.openInBrowser(appContext, url)
                                }
                            },
                        )
                        when (result) {
                            is WorkBuddyLoginManager.Result.Success -> {
                                // Persist the bundle, then mark the instance
                                // as credentialed so the model refresh and
                                // routing gates see a usable provider.
                                WorkBuddyCredentialStore.storeAuthorized(
                                    context = appContext,
                                    instanceId = instanceId,
                                    auth = result.auth,
                                    requestedRegion = region,
                                )
                                providerRepository.saveApiKey(
                                    instanceId,
                                    ProviderRepository.WORKBUDDY_OAUTH_MARKER,
                                )
                                statusMessage = appContext.getString(
                                    R.string.workbuddy_oauth_done,
                                    result.account,
                                )
                                reload()
                                // Pull the tenant's entitlement set right
                                // away — the account-scoped catalog is only
                                // reachable once signed in.
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        providerRepository.refreshModels(target, forceRefresh = true)
                                    }
                                }
                            }
                            is WorkBuddyLoginManager.Result.Failed ->
                                statusMessage = result.message
                        }
                        working = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                enabled = !working && instance != null,
            ) {
                Row {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        stringResource(
                            if (tokens == null) {
                                R.string.workbuddy_oauth_login
                            } else {
                                R.string.workbuddy_oauth_relogin
                            },
                        ),
                    )
                }
            }

            // ── Secondary action: sign out ─────────────────────────────
            if (tokens != null) {
                Spacer(Modifier.height(8.dp))
                MinisOutlinedButton(
                    onClick = {
                        WorkBuddyCredentialStore.clear(appContext, instanceId)
                        // Drop the marker so the instance stops presenting
                        // itself as credentialed (model refresh then falls
                        // back to the bundled placeholder list).
                        providerRepository.deleteApiKey(instanceId)
                        statusMessage = null
                        reload()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    enabled = !working,
                ) {
                    Text(stringResource(R.string.workbuddy_oauth_logout))
                }
            }

            // ── [T-workbuddy-keepalive] Authorization keep-alive ────────
            // Only meaningful once signed in (nothing to keep alive before
            // that), and hidden while a sign-in is in flight to keep the
            // card stable.
            if (tokens != null && !working) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.workbuddy_keepalive_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.workbuddy_keepalive_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = keepAliveEnabled,
                        onCheckedChange = { on ->
                            keepAliveEnabled = on
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    WorkBuddyKeepAlive.setEnabled(appContext, on)
                                }
                            }
                        },
                    )
                }
                if (keepAliveEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.workbuddy_keepalive_interval),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        // Cycle button — matches the Antigravity section's
                        // pattern for 5 discrete cadences.
                        androidx.compose.material3.TextButton(
                            onClick = {
                                val choices = WorkBuddyKeepAlive.INTERVAL_CHOICES_HOURS
                                val next = choices[
                                    (choices.indexOf(keepAliveIntervalH) + 1) % choices.size,
                                ]
                                keepAliveIntervalH = next
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        WorkBuddyKeepAlive.setIntervalHours(appContext, next)
                                    }
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    R.string.workbuddy_keepalive_interval_value,
                                    keepAliveIntervalH,
                                ),
                            )
                        }
                    }
                    // Status line: last pass + outcome; a fatal rejection
                    // surfaces the re-login hint (never auto-cleared).
                    val fatal = WorkBuddyKeepAlive.fatalAccounts(appContext)
                    val lastRun = WorkBuddyKeepAlive.lastRunAt(appContext)
                    val lastResult = WorkBuddyKeepAlive.lastResult(appContext)
                    Text(
                        text = if (lastRun == 0L) {
                            stringResource(R.string.workbuddy_keepalive_never)
                        } else {
                            stringResource(
                                R.string.workbuddy_keepalive_last_run,
                                java.text.SimpleDateFormat(
                                    "MM-dd HH:mm",
                                    java.util.Locale.getDefault(),
                                ).format(java.util.Date(lastRun)),
                                lastResult ?: "—",
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fatal.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.workbuddy_keepalive_fatal_hint,
                                fatal.joinToString("、"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        WorkBuddyKeepAlive.runKeepAliveNow(appContext)
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.workbuddy_keepalive_run_now))
                    }
                }
            }

            // ── Tertiary action: import an existing credential ─────────
            if (tokens == null) {
                Spacer(Modifier.height(8.dp))
                MinisOutlinedButton(
                    onClick = { showImport = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    enabled = !working,
                ) {
                    Text(stringResource(R.string.workbuddy_import_json))
                }
            }
        }
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text(stringResource(R.string.workbuddy_import_json)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.workbuddy_import_json_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    SectionTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = "{\"auth\":{\"accessToken\":\"…\"}}",
                        singleLine = false,
                        maxLines = 6,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val raw = importText
                        val target = instance
                        showImport = false
                        importText = ""
                        if (target == null) return@TextButton
                        working = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    WorkBuddyCredentialStore.importFromJson(
                                        context = appContext,
                                        instanceId = instanceId,
                                        rawJson = raw,
                                        requestedRegion = region,
                                    )
                                }
                            }
                            result.onSuccess { imported ->
                                providerRepository.saveApiKey(
                                    instanceId,
                                    ProviderRepository.WORKBUDDY_OAUTH_MARKER,
                                )
                                statusMessage = appContext.getString(
                                    R.string.workbuddy_oauth_done,
                                    imported.displayName,
                                )
                                reload()
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        providerRepository.refreshModels(target, forceRefresh = true)
                                    }
                                }
                            }.onFailure { e ->
                                statusMessage = appContext.getString(
                                    R.string.workbuddy_import_failed,
                                    e.message?.take(160) ?: "unknown error",
                                )
                            }
                            working = false
                        }
                    },
                    enabled = importText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.workbuddy_import_json_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
