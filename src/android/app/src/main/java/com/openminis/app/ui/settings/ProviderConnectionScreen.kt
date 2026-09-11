package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.openminis.app.ui.util.bringIntoViewOnFocus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.openminis.app.R
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.antigravity.AntigravityBrowserLauncher
import com.openminis.app.provider.antigravity.AntigravityCredentialStore
import com.openminis.app.provider.antigravity.AntigravityLoginBrowser
import com.openminis.app.provider.antigravity.AntigravityLoginManager
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.SectionTextField

private const val TAG = "ProviderConnection"

/**
 * [T-provider-connection-screen] Full configuration page for a single AI
 * provider instance — label, credentials (API key), custom base URL,
 * API format, image endpoint, Azure mode. Opened from the "API & Connection"
 * row on ProviderDetailScreen; the detail screen itself stays focused on the
 * everyday stuff (enable toggle + model picker).
 */
@Composable
fun ProviderConnectionScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId } ?: run {
        onBack()
        return
    }

    // Label editing lives on the detail screen (its title). Here we show the
    // current label so the page has context; connection params are the focus.

    var storedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(instanceId) {
        storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
    }
    var isEditingKey by remember { mutableStateOf(false) }
    var editKeyValue by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    var customBaseURL by rememberSaveable { mutableStateOf(instance.customBaseURL ?: "") }
    var customUserAgent by rememberSaveable { mutableStateOf(instance.customUserAgent ?: "") }

    fun saveBaseURLSettings() {
        // /v1 is appended automatically for all non-Gemini providers (Gemini
        // uses v1beta full-path URLs). effectiveBaseURL guards double-append.
        // [T-antigravity-oauth] Antigravity also rides full-origin URLs.
        val appendV1 = instance.providerType != ProviderType.gemini &&
            instance.providerType != ProviderType.antigravity
        providerRepository.updateInstance(
            instance.copy(
                customBaseURL = customBaseURL.ifBlank { null },
                appendV1Suffix = appendV1,
                customUserAgent = customUserAgent.ifBlank { null },
            )
        )
        AppLogger.info(
            TAG,
            "Saved base URL for ${instance.id}: url='${customBaseURL.ifBlank { "<default>" }}', appendV1=$appendV1, ua='${customUserAgent.ifBlank { "<default>" }}'",
        )
    }

    SettingsScaffold(
        title = instance.label,
        onBack = onBack,
    ) {
        // ─── Credential / API Key ───────────────────────────────────
        // [T-antigravity-oauth] Antigravity instances get the OAuth login
        // section (browser picker + 开始登录 + account status) instead of
        // the API-key editor. Every other type keeps the key editor.
        if (instance.providerType == ProviderType.antigravity) {
            AntigravityOAuthSection(
                instanceId = instanceId,
                providerRepository = providerRepository,
            )
        } else {
            SettingsSection(
                header = stringResource(R.string.provider_list_api_key),
            ) {
                SettingsCardBlock {
                    ApiKeyCredentialBlock(
                        storedKey = storedKey,
                        keyVisible = keyVisible,
                        onToggleVisibility = { keyVisible = !keyVisible },
                        isEditing = isEditingKey,
                        editValue = editKeyValue,
                        onEditValueChange = { editKeyValue = it },
                        onBeginEdit = {
                            isEditingKey = true
                            editKeyValue = storedKey ?: ""
                        },
                        onCancelEdit = {
                            isEditingKey = false
                            editKeyValue = ""
                            keyVisible = false
                        },
                        onSave = {
                            providerRepository.saveApiKey(instanceId, editKeyValue)
                            storedKey = editKeyValue
                            AppLogger.info(TAG, "Saved API key for ${instance.id}")
                            isEditingKey = false
                        },
                    )
                }
            }
        }

        // ─── Custom Base URL ────────────────────────────────────────
        if (instance.providerType != ProviderType.openRouter) {
            // Provider-aware placeholder so the field suggests the real
            // default endpoint for the selected provider instead of the
            // generic example (mirrors AddProviderScreen.defaultUrl).
            val baseUrlPlaceholder = when (instance.providerType) {
                ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
                ProviderType.anthropic -> "https://api.anthropic.com"
                ProviderType.openAI -> "https://api.openai.com"
                // [T-antigravity-oauth] Full-origin upstream default.
                ProviderType.antigravity -> com.openminis.app.provider.antigravity.AntigravityOAuth.DAILY_API_ENDPOINT
                else -> stringResource(R.string.provider_detail_https_api_example_placeholder)
            }
            SettingsSection(header = stringResource(R.string.provider_detail_custom_api_base)) {
                SettingsCardBlock {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SectionTextField(
                            value = customBaseURL,
                            onValueChange = { customBaseURL = it },
                            singleLine = true,
                            placeholder = baseUrlPlaceholder,
                            fieldModifier = Modifier
                                .bringIntoViewOnFocus()
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) saveBaseURLSettings()
                                },
                        )
                        // [T-audit-p2-4] Persistent warning when the endpoint uses
                        // unencrypted HTTP — API keys, messages and tool results
                        // would travel in cleartext over the network.
                        if (customBaseURL.trim().startsWith("http://", ignoreCase = true)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.provider_detail_http_endpoint_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    val showUserAgentField = instance.providerType == ProviderType.openAI ||
                        instance.providerType == ProviderType.anthropic
                    if (showUserAgentField) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.provider_detail_custom_user_agent),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(4.dp))
                            SectionTextField(
                                value = customUserAgent,
                                onValueChange = { customUserAgent = it },
                                singleLine = true,
                                placeholder = stringResource(R.string.provider_detail_custom_user_agent_placeholder),
                                fieldModifier = Modifier
                                    .bringIntoViewOnFocus()
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused) saveBaseURLSettings()
                                    },
                            )
                        }
                    }
                }
            }
        }

        // ─── API Format (OpenAI only) ───────────────────────────────
        if (instance.providerType == ProviderType.openAI) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_api_format),
                footer = if (instance.useResponsesAPI) {
                    stringResource(R.string.provider_detail_api_format_responses_footer)
                } else {
                    stringResource(R.string.provider_detail_api_format_chat_footer)
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !instance.useResponsesAPI,
                            onClick = {
                                if (instance.useResponsesAPI) {
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = false))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                        SegmentedButton(
                            selected = instance.useResponsesAPI,
                            onClick = {
                                if (!instance.useResponsesAPI) {
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = true))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_responses_api)) }
                    }
                }
            }
        }

        // ─── Azure OpenAI ───────────────────────────────────────────
        if (instance.supportsAzureMode) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_azure_openai),
                footer = stringResource(R.string.provider_detail_azure_openai_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.provider_detail_azure_openai),
                    checked = instance.azureMode,
                    onCheckedChange = { on ->
                        providerRepository.updateInstance(instance.copy(azureMode = on))
                        AppLogger.info(TAG, "Set azureMode=$on for ${instance.id}")
                    },
                    showDivider = false,
                )
            }
        }

        // ─── Image Generation Endpoint ──────────────────────────────
        if (instance.supportsImageEndpointSetting) {
            val mode = instance.imageEndpointMode
            SettingsSection(
                header = stringResource(R.string.provider_detail_image_generation),
                footer = when (mode) {
                    ImageEndpointMode.auto ->
                        if (instance.imageEndpointResolved != null) {
                            val resolved = if (instance.imageEndpointResolved ==
                                ImageEndpointMode.imagesGenerations
                            ) "/v1/images/generations" else "/v1/chat/completions"
                            stringResource(
                                R.string.provider_detail_image_endpoint_auto_footer_resolved,
                                resolved,
                            )
                        } else {
                            stringResource(R.string.provider_detail_image_endpoint_auto_footer)
                        }
                    ImageEndpointMode.imagesGenerations ->
                        stringResource(R.string.provider_detail_image_endpoint_images_footer)
                    ImageEndpointMode.chatCompletions ->
                        stringResource(R.string.provider_detail_image_endpoint_chat_footer)
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = mode == ImageEndpointMode.auto,
                            onClick = {
                                if (mode != ImageEndpointMode.auto) {
                                    providerRepository.updateInstance(
                                        instance.copy(imageEndpointMode = ImageEndpointMode.auto),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        ) { Text(stringResource(R.string.provider_detail_image_endpoint_auto)) }
                        SegmentedButton(
                            selected = mode == ImageEndpointMode.imagesGenerations,
                            onClick = {
                                if (mode != ImageEndpointMode.imagesGenerations) {
                                    providerRepository.updateInstance(
                                        instance.copy(imageEndpointMode = ImageEndpointMode.imagesGenerations),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        ) { Text(stringResource(R.string.provider_detail_image_endpoint_images_api)) }
                        SegmentedButton(
                            selected = mode == ImageEndpointMode.chatCompletions,
                            onClick = {
                                if (mode != ImageEndpointMode.chatCompletions) {
                                    providerRepository.updateInstance(
                                        instance.copy(imageEndpointMode = ImageEndpointMode.chatCompletions),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        ) { Text(stringResource(R.string.provider_detail_image_endpoint_chat)) }
                    }
                }
            }
        }
    }
}

/**
 * [T-antigravity-oauth] OAuth login section for Antigravity instances.
 *
 * UI contract (mirrors the CLIProxyAPI management flow):
 *  1. A browser-choice segmented row — 系统浏览器（Chrome 标签页，默认） or
 *     应用内浏览器 — persisted per instance in "antigravity_prefs".
 *  2. A "开始登录" button. On tap: the loopback callback server binds
 *     localhost:51121, the chosen browser opens Google's consent page, and
 *     the flow awaits the redirect (≤5 min). Success stores access/refresh
 *     tokens + email + project id in the encrypted credential store and
 *     triggers a model-list refresh.
 *  3. Status rows: logged-in email, project id, token expiry — plus a
 *     退出登录 action that wipes the stored credential.
 */
@Composable
private fun AntigravityOAuthSection(
    instanceId: String,
    providerRepository: ProviderRepository,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences("antigravity_prefs", android.content.Context.MODE_PRIVATE)
    }

    var browserChoice by remember {
        mutableStateOf(
            prefs.getString("login_browser", AntigravityLoginBrowser.SYSTEM_BROWSER.name)
                ?.let { runCatching { AntigravityLoginBrowser.valueOf(it) }.getOrNull() }
                ?: AntigravityLoginBrowser.SYSTEM_BROWSER
        )
    }

    var email by remember { mutableStateOf<String?>(null) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var expiredText by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        val tokens = AntigravityCredentialStore.loadTokens(appContext, instanceId)
        email = AntigravityCredentialStore.loadEmail(appContext, instanceId)
        projectId = AntigravityCredentialStore.loadProjectId(appContext, instanceId)
        expiredText = if (tokens == null) {
            null
        } else {
            AntigravityCredentialStore.loadExpiredText(appContext, instanceId)
        }
    }

    LaunchedEffect(instanceId) { refreshStatus() }

    SettingsSection(
        header = stringResource(R.string.antigravity_oauth_section_header),
        footer = stringResource(R.string.antigravity_oauth_section_footer),
    ) {
        SettingsCardBlock {
            // ── Browser choice ──
            Text(
                text = stringResource(R.string.antigravity_oauth_browser_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AntigravityLoginBrowser.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = browserChoice == mode,
                        onClick = {
                            browserChoice = mode
                            prefs.edit().putString("login_browser", mode.name).apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AntigravityLoginBrowser.entries.size),
                    ) {
                        Text(
                            when (mode) {
                                AntigravityLoginBrowser.SYSTEM_BROWSER -> stringResource(R.string.antigravity_browser_system)
                                AntigravityLoginBrowser.IN_APP_BROWSER -> stringResource(R.string.antigravity_browser_in_app)
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Login button / progress ──
            if (isLoggingIn) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.antigravity_oauth_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                com.openminis.app.ui.components.MinisButton(
                    onClick = {
                        isLoggingIn = true
                        statusMessage = null
                        scope.launch {
                            val result = AntigravityLoginManager.login(
                                context = appContext,
                                browser = browserChoice,
                                openBrowser = { url ->
                                    AntigravityBrowserLauncher.open(appContext, url, browserChoice)
                                },
                                instanceId = instanceId,
                            )
                            when (result) {
                                is AntigravityLoginManager.Result.Success -> {
                                    statusMessage = appContext.getString(
                                        R.string.antigravity_oauth_done,
                                        result.email ?: "—",
                                    )
                                    // Refresh the model list now that a
                                    // credential exists (force: bypass cache).
                                    val instance = providerRepository.instance(instanceId)
                                    if (instance != null) {
                                        providerRepository.refreshModels(instance, forceRefresh = true)
                                    }
                                }
                                is AntigravityLoginManager.Result.Cancelled ->
                                    statusMessage = appContext.getString(R.string.antigravity_oauth_cancelled)
                                is AntigravityLoginManager.Result.Failed ->
                                    statusMessage = result.message
                            }
                            isLoggingIn = false
                            refreshStatus()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (email == null) {
                            stringResource(R.string.antigravity_oauth_login)
                        } else {
                            stringResource(R.string.antigravity_oauth_relogin)
                        },
                    )
                }
            }

            statusMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Account status + logout ──
        if (email != null) {
            SettingsCardBlock {
                SettingsRow(
                    title = email.orEmpty(),
                    subtitle = listOfNotNull(
                        projectId?.let { appContext.getString(R.string.antigravity_project_prefix) + it },
                        expiredText?.let { appContext.getString(R.string.antigravity_expires_prefix) + it },
                    ).joinToString(" · ").ifEmpty { null },
                    showChevron = false,
                    showDivider = false,
                )
                SettingsRow(
                    title = stringResource(R.string.antigravity_oauth_logout),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        AntigravityCredentialStore.clear(appContext, instanceId)
                        providerRepository.saveApiKey(instanceId, "")
                        scope.launch {
                            val instance = providerRepository.instance(instanceId)
                            if (instance != null) providerRepository.refreshModels(instance, forceRefresh = true)
                            refreshStatus()
                        }
                        AppLogger.info("AntigravityOAuth", "logged out instance $instanceId")
                    },
                    showDivider = false,
                )
            }
        }
    }
}