package com.openminis.app.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.config.ChatActionCatalog
import com.openminis.app.config.ChatMenuPrefs
import com.openminis.app.config.isChatActionAvailable
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.EpisodeMemoryStore
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton

// iOS ChatColors equivalent
internal val ToolCheckColor = Color(0xFF34C759) // iOS .green
internal val ToolErrorColor = Color(0xFFFF3B30) // iOS .red
internal val ToolCancelColor = Color(0xFFFFCC00) // iOS .yellow
// Memory tool accent — matches iOS `.pink` on SF Symbols.
internal val ToolMemoryAccent = Color(0xFFFF2D55)
// Sparkle gradient colors (iOS uses linear gradient)
internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)
internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
private const val ATTACHMENT_PICK_LIMIT = 50

/**
 * [T-android-send-no-autoscroll-behind-preview] Follow-grace window after a
 * user message append: within it the reserve-change pin bypasses the
 * isNearBottom gate (send intent is unambiguous; the freshly-inserted rows
 * make the live anchor transiently read "not at bottom").
 */
private const val SEND_FOLLOW_GRACE_MS = 2_000L

/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 */
private val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

/**
 * Draw a thin scroll thumb on the right edge of a [LazyColumn] (or any
 * scrollable) so the user can see at a glance that the list overflows
 * and is scrollable — mirrors iOS `.scrollIndicators(.visible)` which
 * Compose does not provide out of the box for LazyColumn.
 *
 * The thumb fades in while scrolling / shortly after, similar to the
 * platform scrollbar.
 */
private fun Modifier.verticalScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    width: Dp = 3.dp,
    color: Color = Color(0x55888888),
): Modifier = this.then(Modifier.drawWithContent {
    drawContent()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent
    if (visibleItems.size >= totalItems &&
        visibleItems.first().index == 0 &&
        visibleItems.last().index == totalItems - 1 &&
        visibleItems.first().offset >= 0
    ) {
        // Fully visible, no scroll possible — no thumb.
        return@drawWithContent
    }
    val firstIndex = visibleItems.first().index
    val firstOffsetPx = visibleItems.first().offset.toFloat()
    val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentPx = avgItemSize * totalItems
    val viewportHeight = this.size.height
    if (totalContentPx <= viewportHeight || avgItemSize <= 0f) return@drawWithContent
    val scrollOffsetPx = firstIndex * avgItemSize - firstOffsetPx
    val thumbHeight = (viewportHeight * (viewportHeight / totalContentPx)).coerceAtLeast(24f)
    val maxScroll = (totalContentPx - viewportHeight).coerceAtLeast(1f)
    val maxTop = (viewportHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (scrollOffsetPx / maxScroll * maxTop).coerceIn(0f, maxTop)
    val widthPx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(this.size.width - widthPx - 1f, thumbTop),
        size = androidx.compose.ui.geometry.Size(widthPx, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthPx / 2, widthPx / 2),
    )
})

// [T-android-tool-autoscroll] Combined signal for the streaming auto-follow
// LaunchedEffect. data class so distinctUntilChanged uses structural equality
// — any field flip propagates a tick. Per-block (id, kind, status, length)
// folded into [blockSig] (FNV-1a 64-bit hash) so a RUNNING→SUCCESS flip on a
// tool block, a new block appearing (id flips), or a kind change all wake the
// collector even when growth/size/awaiting alone would have stayed equal.
private data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ChatScreen(
    sessionId: String,
    /**
     * [P0-0] When non-null, scroll to this message once after the list is
     * populated and briefly highlight it. Null (the default) reproduces the
     * pre-P0-0 behaviour exactly — no extra scroll is issued, so the existing
     * follow/anchor state machine is untouched for ordinary chat opens.
     *
     * Consumed once: after the jump the local target is cleared so returning
     * to this screen (config change, back-from-terminal) does not re-jump and
     * fight the user's own scrolling.
     */
    focusMessageId: String? = null,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    experienceMemoryStore: EpisodeMemoryStore? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
    /** [T-new-chat-menu-entry] "New Chat" from the chat "..." menu: caller
     *  navigates to a fresh draft chat (same funnel as the session list's
     *  new-chat button), replacing this chat on the back stack. */
    onNewChat: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** Open the in-app terminal with [command] pre-filled at the prompt
     *  (no trailing newline — the user reviews and presses Enter manually).
     *  Wired to the top-right Terminal button on a shell_execute ToolDetailSheet. */
    onOpenTerminalWithCommand: (command: String) -> Unit = {},
    /** "Move to…" capsule (T51): called when the user picks a target session
     *  from MoveToSessionSheet after a share-injected turn. The caller is
     *  responsible for navigating; this screen has already stashed the
     *  pending transfer in [ChatViewModelStore.stashPendingTransfer]. */
    onMoveToSession: (sessionId: String) -> Unit = {},
    onBrowseChatFiles: () -> Unit = {},
    /** T150: open FilePreviewScreen for a non-image attachment in a user bubble. */
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit = {},
    /** [T-android-modelpicker-group-edit] Navigate to the Model Groups
     *  management screen — wired to the "Edit" button on the model picker's
     *  Model Groups section header. */
    onModelGroupsClick: () -> Unit = {},
    /** Open another conversation from the chat-history drawer. The caller
     *  navigates to that chat (same funnel as the session list's onSessionClick). */
    onOpenSession: (String) -> Unit = {},
    /** Open Settings from the chat-history drawer footer. */
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Scoped to a process-level per-session ViewModelStore (ChatViewModelStore)
    // so the ViewModel and its viewModelScope survive:
    //   - configuration changes (rotation — NavBackStackEntry still alive)
    //   - leaving the chat screen via popBackStack (NavBackStackEntry destroyed)
    // The VM is released only when the session is deleted (see SessionListViewModel).
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            experienceMemoryStore = experienceMemoryStore,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )
    // [T-android-larky-longsession-followup] Consume the tail-windowed
    // view instead of the canonical full list. For sessions with ≤300
    // messages this is the SAME reference (zero overhead); for longer
    // sessions (Larky's 600+) it caps at INITIAL_VISIBLE_MESSAGE_CAP and
    // grows in steps when the user reaches the top via [viewModel.loadOlderMessages].
    // Callers needing the full history (compact / fork / regenerate / send)
    // continue to read viewModel.messages directly inside the VM.
    val messages by viewModel.uiMessages.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val canResume by viewModel.canResume.collectAsState()
    val error by viewModel.error.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val sessionCategory by viewModel.sessionCategory.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val availableGroups by viewModel.availableGroups.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsState()
    val showMemorySheet by viewModel.showMemorySheet.collectAsState()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsState()
    val selectedGroupName by viewModel.selectedGroupName.collectAsState()
    val providerName by viewModel.providerName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Hoisted to ChatViewModel so it survives ChatScreen disposal/recomposition
    // across forward navigation (file preview, env vars, etc.); see
    // ChatViewModel.listState for the why.
    val listState = viewModel.listState
    // T325: draft persists on the VM so navigation (e.g. push EnvVars and
    // pop back) doesn't wipe what the user has typed. Mirrors iOS
    // `AIChatView` which binds the composer against `vm.inputText`.
    val inputText by viewModel.inputText.collectAsState()

    // ─── T51: Share Injection + Move-to capsule ───────────────────────
    // Drain any pending share buffered by ShareCoordinator (cold start =
    // bufferVersion already non-zero on first composition; warm start =
    // version increments while the user is mid-session). Runs on every
    // bufferVersion bump.
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val pending = com.openminis.app.share.ShareCoordinator.consumeBuffer(context)
            ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId",
        )
        val sharedDir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context)
        for (item in pending.items) {
            when (item.kind) {
                com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (inputText.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    viewModel.setInputText(
                        inputText + sep + item.value +
                            if (needsTrailingSpace) " " else "",
                    )
                }
                com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        viewModel.markShareInjected()
        com.openminis.app.share.SharedShareStore.cleanSharedFiles(context)
    }

    // T311: publish "this is the active chat" while ChatScreen is composed,
    // so `minis-config session.*` reads/writes target it. Mirrors iOS
    // `AIChatViewModel.activeSessionId` which is updated on appear / disappear.
    // [T-HANG-DIAG] capture the application context so we can read the
    // current hang count from non-composable scopes below. LocalContext is
    // already used elsewhere in this file via `context`, but DisposableEffect
    // is a non-composable scope so we lift the read up here.
    val tHangDiagAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.DisposableEffect(sessionId) {
        ChatViewModelStore.setActiveSession(sessionId)
        // [T-HANG-DIAG] enter / dispose markers around the ChatScreen lifetime
        // so we can correlate "user tapped session X" → loadSession timings
        // and any subsequent hang record. Removable by grepping out
        // `[T-HANG-DIAG]` from this file.
        println(
            "[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=" +
                com.openminis.app.diagnostics.HangDetector.currentHangCount(tHangDiagAppContext),
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            ChatViewModelStore.setActiveSession(null)
            // T-android-new-chat-empty-residue: drop sessions materialised by
            // a settings toggle (ensureSession via /memory, /thinking, etc.)
            // but never sent a real message. VM guards on streaming + DB count
            // so an in-flight agent or non-empty session is left alone.
            // Skip cleanup on configuration changes (e.g. rotation) — the
            // composable is about to re-mount with the same session and its
            // pending attachments would be lost if we released the ViewModel.
            val activity = context as? android.app.Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanupIfEmptyOnExit()
            }
        }
    }

    // Hang-detector quiet-period reset: if the user lands on a chat session
    // and stays for 10s without the watchdog firing again, the previous
    // hang count was a transient blip and the breaker can release. The call
    // itself is cheap — early-returns when the count is already zero.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        kotlinx.coroutines.delay(10_000)
        com.openminis.app.diagnostics.HangDetector.markHealthyTick()
    }

    // Drain any pending Move-to transfer when entering this session — the
    // source ChatScreen stashed (inputText + attachments) into the global
    // ChatViewModelStore.pendingTransfer slot before navigating here.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        val transfer = ChatViewModelStore.consumePendingTransfer() ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        // Clear any stale unsent attachments on the target session before
        // injecting (mirrors iOS injectPendingTransferIfNeeded).
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val sep = if (inputText.isNotEmpty()) "\n" else ""
            viewModel.setInputText(inputText + sep + transfer.inputText)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
        viewModel.markShareInjected()
    }

    // Mirrors `inputText` for the BasicTextField but tracks selection so we
    // can position the cursor (e.g. AFTER the leading "/" when the slash
    // button inserts it) — a plain String overload would reset cursor to 0
    // on every external write.
    var inputFieldValue by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    // T217-2: suppress IME commits arriving briefly after send. clearFocus
    // triggers finishComposingText, which makes voice/Pinyin IMEs commit
    // their pending candidate back through onValueChange even after we
    // cleared inputText. Drop those late commits during a short window.
    var lastSendTimeMs by remember { mutableStateOf(0L) }
    // Commit the composer input-mode pref on send. Voice input was removed, so
    // every composition is now "text"; the old voiceUsedSinceClear tracker and
    // the VoiceCorrection vocabulary miner (which only fed voice STT correction)
    // went with it.
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = false)
    }
    // [T-android-send-no-autoscroll-behind-preview] Timestamp of the most
    // recent USER message append, stamped in LE(messages.size) so it covers
    // every origin (send button, Enter, RPC, enqueue-while-streaming). Used
    // as the follow-grace window for the reserve-change pin. Deliberately
    // separate from lastSendTimeMs above — that one also drives the 300ms
    // IME-residue suppression in the composer and must stay UI-send-only.
    var lastUserAppendMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (inputFieldValue.text != inputText) {
            // [T-android-slash-menu-align-ios-prepend] Honor a one-shot caret
            // override from the slash flow (prepend "/ " → caret 1; insert
            // "/<skill> " → caret after the prefix). Read-and-clear so it
            // applies exactly once; otherwise default the caret to the end
            // (existing behavior). Coerce into bounds defensively.
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: inputText.length
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                // T217: explicitly drop any pending IME composing buffer so voice
                // recognition / Pinyin candidates don't get re-committed back into
                // the field after send (mirrors iOS unmarkText in AIChatView.swift
                // updateUIView L5638).
                composition = null,
            )
        }
    }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Mirror of iOS `inputFocused` — needed so the swipe-up-on-empty-input
    // gesture only pops the keyboard when it's actually collapsed.
    var inputFocused by remember { mutableStateOf(false) }

    // --- Swipe-up-to-send (parity with iOS AIChatView.swift) ---------------
    // Drag progress 0..1 as fraction of the trigger distance. Drives the
    // floating send-arrow hint + "Release to send" capsule overlay. Only
    // updated while the input has non-empty text.
    var sendSwipeProgress by remember { mutableStateOf(0f) }
    // Live fingertip position inside the input bar (px). Hint floats ~60dp
    // above this point so it isn't hidden under the user's thumb.
    var sendSwipeLocation by remember { mutableStateOf(Offset.Zero) }
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    // Match iOS: haptic + capsule full-opacity + release-fires-send all
    // engage at this fraction (below 1.0 so user gets earlier confirmation).
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val coroutineScope = rememberCoroutineScope()

    var showModelPicker by remember { mutableStateOf(false) }
    // [T-android-thinking-badge-navbar] Whether the thinking-level sheet
    // (opened by tapping the navbar thinking badge) is presented. Mirrors iOS
    // AIChatView.showThinkingLevelSheet.
    var showThinkingLevelSheet by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    // [T-input-history] "Input History" sheet visibility, mirrors rikkahub's
    // top-bar Chat Options preview. Treated as a pinned, always-visible
    // top-bar action (like New Chat) rather than a "..." menu entry — both
    // are high-frequency actions the user wants immune to the "hide empty
    // menu" collapse.
    var showInputHistorySheet by remember { mutableStateOf(false) }
    // [T-input-history] Focus-jump target and highlight anchor. Hoisted from
    // inside the Scaffold content lambda so the top-bar "input history" button
    // (rendered before the content lambda) can write pendingFocusId and
    // trigger a scroll + highlight without needing a separate channel.
    // Semantics mirror iOS ChatView.focusMessageId nav param.
    var pendingFocusId by remember(sessionId, focusMessageId) {
        mutableStateOf(focusMessageId?.takeIf { it.isNotBlank() })
    }
    var highlightedMessageId by remember(sessionId) { mutableStateOf<String?>(null) }
    var showSkillsSheet by remember { mutableStateOf(false) }
    // [T-mcp-integration-android] MCPs-in-Session sheet visibility.
    var showMcpsSheet by remember { mutableStateOf(false) }
    var showTokenUsageSheet by remember { mutableStateOf(false) }
    // [bottom-toolbar-customizable] Export format picker shared by the "..." menu
    // and the history-drawer footer (replaces the old inline submenu).
    var showExportFormatSheet by remember { mutableStateOf(false) }
    // T185: Move-to-session sheet visibility. Hoisted to the top of
    // ChatScreen so the trigger (capsule inside the composer) and the
    // sheet body (rendered later in the layout tree) share the same
    // backing state without needing fragile scope wiring.
    var showMoveSheet by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    // [T-android-enhanced-cache] First-enable confirmation dialog visibility.
    var showEnhancedCacheDialog by remember { mutableStateOf(false) }

    // Bridge VM's slash-command "/clear" request into local Compose state so
    // the menu and slash-command entry points share a single confirmation
    // dialog instance. ack the VM flag immediately to avoid re-firing on
    // recomposition.
    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsState()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialog = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    // [bottom-toolbar-customizable] Live resolved order + visibility/pin state
    // for the customizable action pool (top-right "..." menu + history-drawer
    // footer). Re-read on every appearance_prefs change (Chat Menu settings
    // screen, minis-config write, backup restore) so this screen never needs a
    // restart to pick up a new arrangement.
    val chatActions = rememberChatActionState(context)
    // [bottom-toolbar-customizable] Session memory toggle, hoisted to the top of
    // the composable so both the menu gate and the footer availability filter
    // (footerSpecs below) read the same live value.
    val menuMemoryEnabled by viewModel.memoryEnabled.collectAsState()

    // [bottom-toolbar-customizable] Single dispatch point shared by the "..."
    // menu and the history-drawer footer. Each action resolves to exactly one
    // side effect here, so the same feature can never open a different sheet
    // depending on which entry point triggered it (see ExportFormatSheet for
    // why EXPORT in particular needed this).
    fun dispatchChatAction(key: String) {
        when (key) {
            ChatMenuPrefs.TERMINAL -> onOpenTerminal()
            ChatMenuPrefs.BROWSER -> viewModel.toggleBrowserSheet()
            ChatMenuPrefs.CHAT_FILES -> onBrowseChatFiles()
            ChatMenuPrefs.SESSION_SKILLS -> showSkillsSheet = true
            ChatMenuPrefs.SESSION_MCPS -> showMcpsSheet = true
            ChatMenuPrefs.SESSION_MEMORY -> viewModel.toggleMemorySheet()
            ChatMenuPrefs.SLASH_COMMANDS -> {
                if (viewModel.showSlashMenu.value) {
                    viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                } else {
                    viewModel.setInputText(viewModel.showSlashMenuOverInput(inputText))
                }
                runCatching { inputFocusRequester.requestFocus() }
                keyboardController?.show()
            }
            ChatMenuPrefs.EXPORT -> showExportFormatSheet = true
            ChatMenuPrefs.TOKEN_USAGE -> showTokenUsageSheet = true
            ChatMenuPrefs.SETTINGS -> onOpenSettings()
        }
    }

    // "Choose Photos & Videos" — uses the Photo Picker on Android 13+ via the
    // PickMultipleVisualMedia contract; AndroidX falls back to
    // ACTION_OPEN_DOCUMENT on older versions. Mirrors iOS PHPicker
    // (.imagesAndVideos, selectionLimit=50). T129: switched from single to
    // multi-select with a 50-item cap — picks above 50 are truncated and we
    // toast the user so they aren't silently dropped.
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ATTACHMENT_PICK_LIMIT,
        ),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val defaultName = if (isVideo) "video.mp4" else "image.jpg"
            val fileName = getFileName(context, uri) ?: defaultName
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = mimeType,
                    // Videos are routed as DOCUMENT for now — vision pipeline only
                    // handles images today; videos still upload as raw files so
                    // tools that read them (e.g. ffmpeg) get the bytes.
                    kind = if (isVideo) InputAttachment.Kind.DOCUMENT else InputAttachment.Kind.IMAGE,
                ),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT items were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // "Take Photo" — Bug 1 in the MIUI feedback report had this silently
    // drop photos because the default TakePicture contract trusts
    // resultCode, and MIUI's camera occasionally returns CANCELED even
    // after writing the file (or OK with the file flushed late). We use
    // StartActivityForResult directly and trust the filesystem instead:
    // if the staging file has nonzero length, we got a photo.
    // [T-android-camera-rotate-lost-photo] MainActivity has no
    // configChanges="orientation", so capturing in one orientation and
    // returning in another RECREATES the Activity. These pending handles must
    // therefore survive the recreate — `remember` is reset on recomposition
    // after recreation, so the ActivityResult callback would see a null uri
    // and silently drop the just-taken photo (gallery picks are unaffected:
    // their result Uri arrives directly in-callback). `rememberSaveable`
    // persists through savedInstanceState: Uri is Parcelable; the staging
    // File is saved as its absolute path string and rebuilt on read.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // [T-android-overlay-hide-camera] Release the overlay-suppress
        // gate as soon as we hear back from the camera Activity (success,
        // cancel, or system kill). Without this the floating overlay
        // would stay suppressed indefinitely after a single capture.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
        val uri = pendingCameraUri
        val file = pendingCameraFilePath?.let { java.io.File(it) }
        pendingCameraUri = null
        pendingCameraFilePath = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        // Don't trust resultCode on MIUI — check the file.
        val ok = file.exists() && file.length() > 0
        if (ok) {
            val fileName = getFileName(context, uri) ?: file.name
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = "image/jpeg",
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        } else {
            AppLogger.warning(
                "Camera",
                "capture failed: rc=${result.resultCode}, file=${file.name} len=${file.length()}",
            )
            file.delete()
        }
    }
    val launchCamera: () -> Unit = {
        val (uri, file) = createCameraOutputUri(context)
        pendingCameraUri = uri
        pendingCameraFilePath = file.absolutePath
        val intent = android.content.Intent(
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
        ).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [T-android-overlay-hide-camera] Suppress the floating bg-overlay
        // BEFORE handing off to the system camera. The camera Activity
        // takes foreground, which by #451's rule would otherwise satisfy
        // "Minis backgrounded → show overlay" and the capsule would draw
        // on top of the viewfinder. Cleared in the ActivityResult callback.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }
            .onFailure {
                AppLogger.warning("Camera", "launch failed: ${it.message}")
                // Launch never reached the camera Activity — release the
                // suppress flag here since the result callback won't fire.
                com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
                pendingCameraUri = null
                pendingCameraFilePath = null
                file.delete()
            }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    // App-icon quick action: when the user launched via
    // `minis://action/camera_chat`, auto-open the camera on first compose.
    // Consumed exactly once so re-entering the chat later does NOT re-trigger.
    // (The old voice quick-action variant was removed with the mic button.)
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // File picker launcher — T129: multi-select via OpenMultipleDocuments
    // (GetContent has no multi-select equivalent). The launch arg is now a
    // mime-type array; "*/*" stays as the wildcard. Selections above
    // ATTACHMENT_PICK_LIMIT are truncated with a toast so silent drops can't
    // happen. OpenMultipleDocuments returns persistable URIs by default
    // (good — survives process death better than the GetContent stream).
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val fileName = getFileName(context, uri) ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = if (mimeType.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
            viewModel.addAttachment(
                InputAttachment(fileName = fileName, uri = uri, mimeType = mimeType, kind = kind)
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT files were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Android system permission launcher for agent tools (e.g. location).
    //
    // The launcher's `results` map can't be trusted alone: on several Android
    // versions `RequestMultiplePermissions` returns an empty map (or `false`
    // entries) for permissions that were already granted and thus didn't need
    // a dialog. Re-query the live permission state via checkSelfPermission to
    // decide success — this is what actually matters to the caller.
    val currentPermissionsRef = remember { mutableStateOf<Array<String>>(emptyArray()) }
    val androidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val perms = currentPermissionsRef.value
        val grantedNow = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        OffloadPermissionManager.respondToAndroidPermission(grantedNow)
    }
    val pendingAndroidPermission by OffloadPermissionManager.pendingAndroidPermission.collectAsState()
    LaunchedEffect(pendingAndroidPermission) {
        val req = pendingAndroidPermission ?: return@LaunchedEffect
        val perms = req.permissions.toTypedArray()
        // Short-circuit when everything's already granted — some OEM builds
        // launch a no-op dialog that still flashes on screen otherwise.
        val alreadyGranted = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            OffloadPermissionManager.respondToAndroidPermission(true)
            return@LaunchedEffect
        }
        currentPermissionsRef.value = perms
        androidPermissionLauncher.launch(perms)
    }

    val tagScroll = "ChatScrollFollow"
    // Scroll wrappers used by every code path that mutates the LazyColumn
    // position. Kept as named lambdas so re-enabling per-call telemetry
    // (during a scroll-positioning regression) is a one-line edit here
    // instead of changing 20+ call sites. Currently silent.
    val tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit = { source, idx, off ->
        // [T-android-top-drag-jump] TEMP: log every programmatic scroll's source
        // so we can see which one fights the user near the top. Remove after fix.
        AppLogger.debug(
            "ScrollSrc",
            "scrollToItem src=$source idx=$idx off=$off canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset} inProgress=${listState.isScrollInProgress}",
        )
        runCatching { listState.scrollToItem(idx, off) }
        Unit
    }
    val tracedScrollBy: suspend (source: String, delta: Float) -> Unit = { source, delta ->
        AppLogger.debug(
            "ScrollSrc",
            "scrollBy src=$source delta=$delta canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset}",
        )
        runCatching { listState.scrollBy(delta) }
        Unit
    }
    // T-android-jank-profile: gate verbose scroll telemetry behind a constant
    // so every snapshotFlow / derivedStateOf body in this file can cheaply
    // skip the AppLogger.debug call (which builds a long format string and
    // writes a daily log file). Flip locally when debugging scroll behavior.
    val verboseScrollLogs = false

    // ─── T120: scroll-follow rewrite (supersedes T66 / T92 / T99 / T100 / T101 / T112) ───
    //
    // Five iterations of "fight the LazyColumn" (anchor lock, fling-settle
    // gate, isStreaming/lastToolCount/lastAwaiting force-follow LEs) never
    // truly stopped the streaming jitter. Survey of production Compose
    // chat clients (google-ai-edge/gallery, GetStream/stream-chat-android-ai,
    // lambiengcode/compose-chatgpt-kotlin-android-chatbot, Taewan-P/gpt_mobile)
    // showed a consistent pattern:
    //
    //   1. Trust reverseLayout's native bottom anchor — do not call
    //      scrollToItem(0) on every streaming token.
    //   2. Auto-scroll only on TERMINAL events (user sends, IME opens,
    //      stream finishes) — never per-token.
    //   3. Treat "user scrolled away" as a derived value of the current
    //      list position, not a stateful flag mutated by a gesture flow.
    //   4. Provide a JumpToBottom FAB as the universal escape hatch
    //      (already present in this file).
    //
    // What was removed
    //   - Anchor-lock LaunchedEffect (T92 / T99 / T112) — Compose's
    //     reverseLayout already keeps a fixed item anchored, the lock
    //     was fighting that.
    //   - userScrolledAway state mutation in two snapshotFlow collectors —
    //     replaced by a single derivedStateOf<Boolean>.
    //   - LE(isStreaming) edge force-follow.
    //   - LE(lastToolCount) force-follow.
    //   - LE(lastAwaiting) force-follow.
    //   - LE(bottomReserve) inside the toolbar block.
    //   - LE(messages.size) for assistant/tool/system rows — only the
    //     user-send branch survives, because sending is the one event
    //     where "follow the new turn" is unambiguously the user's intent.
    //
    // What stayed
    //   - User-action scroll calls at the send button, retry buttons,
    //     and the JumpToBottom FAB — those are direct user intent.
    //   - reverseLayout=true on the LazyColumn — handles "stick to
    //     bottom while user is at bottom" natively.

    // T128: tightened from 90 dp (google-ai-edge/gallery) to 32 dp.
    // 90 dp made the JumpToBottom FAB appear well before the user had
    // really left the bottom — users reported the "Quick to bottom" button
    // appearing too often. 32 dp is roughly half the floating tool-bar height, so the
    // visual definition of "at bottom" lines up with what the user sees.
    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    // T138 phase 2 v3: ground-truth bottom test via layoutInfo. If
    // LazyList currently renders the visual-bottom item (data-index 0
    // under reverseLayout) and its bottom edge sits within `threshold`
    // px of the viewport bottom, the user is visually at the bottom.
    // `firstVisibleItemIndex` is unreliable here: when a single message
    // emission expands into N tool / text flat items, firstVisible
    // drifts by N in one frame (logcat showed jumps of 5+ on a
    // multi-tool turn). Anchor on the rendered set instead.
    val isNearBottom = remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val viewportEnd = info.viewportEndOffset
            val itemBottom = bottomItem?.let { it.offset + it.size } ?: Int.MIN_VALUE
            val gap = viewportEnd - itemBottom
            // T173: when the bottom row is taller than the viewport (e.g. one
            // big assistant message with code blocks), `gap` is permanently
            // hugely negative even when the user is anchored at the bottom —
            // because reverseLayout pins index 0's *bottom* to the viewport
            // bottom, but the item's geometric bottom is below the viewport
            // (it extends downward off-screen in layoutInfo terms). The
            // earlier `gap < threshold` test happened to be true in that
            // case, but it ALSO stayed true as the user scrolled up by
            // thousands of px — settle-after-interaction then snapped them
            // right back. Use the LazyListState anchor instead: under
            // reverseLayout, "at bottom" ⇔ index 0 is the first item AND
            // its scroll offset is within `threshold` px. Any drag upward
            // grows firstVisibleItemScrollOffset past threshold instantly,
            // so the user's intent flips into userScrolledAway.
            val firstIdx = listState.firstVisibleItemIndex
            val firstOff = listState.firstVisibleItemScrollOffset
            // [T-android-scroll-isnearbottom-bug] Anchor authority lives on
            // listState.firstVisibleItem(Index|ScrollOffset). Previous form
            // required `bottomItem != null` too, but during a fresh measure
            // pass visibleItemsInfo can transiently be empty even when the
            // user IS at the bottom (firstIdx=0 / firstOff=0). The empty
            // window read as "not at bottom" and propagated to:
            //   - reserve-change SKIP at the bottom (recovery yank lost)
            //   - scroll-to-bottom FAB shown on a session that's actually
            //     bottom-anchored
            //   - trailing-row pin gated on isNearBottom failing
            // See /tmp/fix_scroll_diagnosis.md. Anchor on firstIdx/firstOff
            // alone — they survive the measure window.
            val result = firstIdx == 0 && firstOff <= nearBottomThresholdPx.toInt()
            // T-android-jank-profile: was logging on every scroll frame (this
            // is a derivedStateOf body — it re-runs when any of
            // listState.layoutInfo / firstVisibleItemIndex /
            // firstVisibleItemScrollOffset / canScrollForward / etc. change,
            // i.e. ~60 times/second during a scroll fling). String-building
            // + file write per frame measurably contributed to scroll jank.
            // Gate behind a debug toggle so the log path stays available for
            // future scroll-debugging sessions but doesn't ship by default.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "isNearBottom: bottomVisible=${bottomItem != null} itemBottom=$itemBottom viewportEnd=$viewportEnd gap=$gap threshold=${nearBottomThresholdPx.toInt()} firstVisible=$firstIdx firstOffset=$firstOff canScrollForward=${listState.canScrollForward} canScrollBackward=${listState.canScrollBackward} totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} isScrollInProgress=${listState.isScrollInProgress} → $result",
                )
            }
            result
        }
    }
    // T170: derived "does content actually overflow the viewport?". Mirrors
    // iOS where `maxOffset > 0` naturally hides the FAB on short sessions.
    // Without this, an IME-driven synthetic drag-stop on a short chat could
    // pin the FAB on screen until the keyboard closed.
    val contentOverflows = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val visibleSum = info.visibleItemsInfo.sumOf { it.size }
            val sumExceedsViewport = visibleSum > viewportSize
            val result = canScroll || moreItemsThanVisible || sumExceedsViewport
            // T-android-jank-profile: gate per-frame derivedStateOf logs.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "contentOverflows: canScroll=$canScroll moreItems=$moreItemsThanVisible sumExceeds=$sumExceedsViewport visibleSum=$visibleSum viewport=$viewportSize total=${info.totalItemsCount} visible=${info.visibleItemsInfo.size} → $result",
                )
            }
            result
        }
    }

    // [T-android-scroll-to-first-message] One-screen distance checks driving the
    // floating scroll buttons, mirroring iOS (T-ios-scroll-to-first-message):
    //   - down-button: shown whenever scrolled away from the bottom (existing
    //     userScrolledAway gate below).
    //   - up-button (scroll to first/oldest message): shown ONLY in the MIDDLE
    //     region — more than one screen from BOTH ends (isFarFromTop &&
    //     isFarFromBottom) — so it never clutters the at-bottom resting state
    //     and never appears on short chats.
    // The list is reverseLayout=true: index 0 is the NEWEST message (visual
    // bottom), the highest index is the OLDEST (visual top). "Far from bottom" =
    // scrolled up away from index 0 by > 1 viewport; "far from top" = the oldest
    // item is still > 1 viewport below the viewport top.
    // [T-android-scroll-to-first-message] "Far from an end" = that end's edge
    // message has been scrolled entirely off-screen by MORE THAN one viewport.
    // Anchoring on the edge item's actual pixel position (not "is it fully at
    // the bottom") avoids the bug where the up-button appeared less than a
    // screen from the bottom: it now requires the newest message to be a full
    // viewport above the viewport bottom before the middle region begins.
    // [T-android-scroll-to-first-message] Distance from each end, measured in
    // viewports. Item heights vary wildly (a one-line bubble vs a screen-tall
    // code block), so anchoring on a single edge item's pixel position breaks
    // once it scrolls off (the earlier "index 0 not fully at bottom" check made
    // the up-button appear less than a screen from the bottom). Instead estimate
    // the scrolled distance: (whole items between the viewport and the end) ×
    // average visible item height + the partial offset of the boundary item.
    // The up-button shows only when BOTH ends are > one viewport away.
    // [T-android-scroll-to-first-message] Compose's LazyListLayoutInfo exposes
    // sizes of CURRENTLY visible items only — no contentSize / contentOffset
    // equivalent to iOS's UIScrollView. Earlier estimations (off-screen item
    // count × avg/min visible-item size) misfired badly because one assistant
    // message expands into many FlatChatItems (header, several markdown
    // blocks, tool blocks, typing indicator); the index count balloons out
    // of proportion to actual pixel distance, so the up-button kept popping
    // up right above the input bar when only a tool-block + header lay
    // off-screen.
    //
    // Instead we OBSERVE: every time an item enters the viewport, cache its
    // (index → size). As the user scrolls we accumulate ground truth for
    // every index we've ever seen. Distance to either end then = sum of
    // cached sizes for the off-screen indices we know about, plus the
    // visible items' real partial overhang. Indices we've never seen still
    // contribute zero — that's a strict lower bound, so we can only
    // under-show the button, never flash it near an end.
    val itemSizeByIndex = remember(listState) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { vis ->
                for (it in vis) {
                    val cached = itemSizeByIndex[it.index]
                    if (cached == null || cached != it.size) itemSizeByIndex[it.index] = it.size
                }
            }
    }
    // [T-android-scroll-fab-first-entry] Average observed item size, used to
    // estimate the height of indices we've never had on-screen. The pure
    // cache-only approach (b58e9515) was one-sided: belowSum (already-scrolled-
    // past items, cached) worked, but aboveSum summed indices we hadn't reached
    // yet, which are NEVER cached at the moment they're off-screen ABOVE — so
    // aboveSum stayed 0 forever and the up-button never appeared (logged:
    // aboveSum=0 across an entire top-scroll, even with all 68 items eventually
    // cached). Estimating unknown indices by the running average makes BOTH
    // ends symmetric and direction-independent. The average is a real measured
    // mean (not a wild min/avg-of-visible extrapolation that the commit comment
    // warned against), so it tracks actual pixel distance closely enough for a
    // one-viewport threshold.
    val avgItemSize = remember(listState, itemSizeByIndex) {
        derivedStateOf {
            val sizes = itemSizeByIndex.values
            if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        }
    }
    fun sizeAt(index: Int): Int = itemSizeByIndex[index] ?: avgItemSize.value
    val isFarFromBottom = remember(listState, itemSizeByIndex, avgItemSize) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val visible = info.visibleItemsInfo
            if (viewportH <= 0 || visible.isEmpty()) return@derivedStateOf false
            // [T-android-scroll-fab-reversed] At the very bottom the list cannot
            // scroll forward; never report "far from bottom" there. This is the
            // authoritative at-bottom signal under reverseLayout. (The earlier
            // nearestBottomOverhang term wrongly reported far-from-bottom=true at
            // rest when the newest message (index 0) was taller than the
            // viewport — its content box extends below the fold so the overhang
            // measured ~1938px > viewportH even though index 0's bottom IS pinned
            // to the viewport bottom. Confirmed in ScrollFAB2 logs:
            // nbIdx=0 nbOver=1938 belowSum=0 canFwd=false → falsely true.)
            if (!listState.canScrollForward) return@derivedStateOf false
            // reverseLayout: index 0 = newest = visual bottom.
            val nearestBottom = visible.minByOrNull { it.index }!!
            // Sum of (cached, else avg-estimated) sizes for the WHOLE items below
            // the viewport: indices 0..nearestBottom.index-1. We intentionally do
            // NOT add a partial-overhang term for nearestBottom — under
            // reverseLayout a tall bottom item makes that term spuriously large.
            // The whole-items sum is the scrolled-past distance we care about.
            var belowSum = 0L
            for (i in 0 until nearestBottom.index) {
                belowSum += sizeAt(i)
            }
            belowSum > viewportH
        }
    }
    val isFarFromTop = remember(listState, itemSizeByIndex, avgItemSize) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val visible = info.visibleItemsInfo
            if (total == 0 || viewportH <= 0 || visible.isEmpty()) return@derivedStateOf false
            // Symmetric to isFarFromBottom: at the top the list cannot scroll
            // backward; never report "far from top" there.
            if (!listState.canScrollBackward) return@derivedStateOf false
            // reverseLayout: highest index = oldest = visual top.
            val nearestTop = visible.maxByOrNull { it.index }!!
            // Whole items above the viewport (older): nearestTop.index+1..total-1.
            // Unmeasured on first entry (scrolled up from the bottom), so estimate
            // via the running average. No partial-overhang term, mirroring above.
            var aboveSum = 0L
            for (i in (nearestTop.index + 1) until total) {
                aboveSum += sizeAt(i)
            }
            aboveSum > viewportH
        }
    }

    // T138 phase 2 v3: separate "user scrolled away" intent from listState
    // position. isNearBottom flips false during the transient window where
    // new items are inserted at index 0 but listState still anchors on the
    // previous item — that is NOT user intent. Drive auto-follow off
    // `userScrolledAway` which only toggles on real user drags.
    var userScrolledAway by remember { mutableStateOf(false) }

    // [T-android-scroll-fab-reversed] TEMP diagnostic — capture BOTH FABs'
    // gates so we can verify the matrix (bottom=none, middle=both, top=down
    // only) and why the down-FAB is missing at the top. Remove after fix.
    LaunchedEffect(listState) {
        snapshotFlow {
            val up = isFarFromTop.value && isFarFromBottom.value && messages.isNotEmpty()
            val down = userScrolledAway && contentOverflows.value && messages.isNotEmpty()
            "FABs up=$up down=$down | farTop=${isFarFromTop.value} farBottom=${isFarFromBottom.value} scrolledAway=$userScrolledAway overflow=${contentOverflows.value} canFwd=${listState.canScrollForward} canBwd=${listState.canScrollBackward}"
        }.collect { AppLogger.debug("ScrollFAB2", it) }
    }

    // T-drag-send-queue: shared send-or-enqueue handler used by BOTH the
    // send-button tap and the swipe-up-to-send drag. Routes through
    // `viewModel.sendMessage(...)` which internally dispatches to
    // `enqueuePrompt()` when `_isStreaming.value` is true, so the message is
    // queued rather than dropped when the agent loop is mid-flight. Slash-
    // command input short-circuits to the command runner (mirrors the tap
    // path). Caller decides whether to invoke this — gating (canActivate,
    // armFraction, swipedUp) stays at the call site.
    val performSendOrEnqueue: (String) -> Unit = handler@{ rawText ->
        if (viewModel.tryExecuteInputAsSlashCommand(rawText)) {
            viewModel.setInputText("")
            keyboardController?.hide()
            focusManager.clearFocus()
            return@handler
        }
        lastSendTimeMs = System.currentTimeMillis()
        viewModel.setInputText("")
        keyboardController?.hide()
        focusManager.clearFocus()
        viewModel.sendMessage(rawText)
        noteSendForInputModePref()
        userScrolledAway = false
        coroutineScope.launch {
            tracedScrollToItem("SEND-PATH/initial", 0, 0)
            kotlinx.coroutines.delay(100)
            tracedScrollToItem("SEND-PATH/settle", 0, 0)
        }
    }
    // T196: timestamp of the last drag-stop. The streaming auto-follow LE
    // below has three stages (initial scroll → re-pin after frame → settle
    // after 220 ms) any of which can fire on the *next* token after a drag
    // ends. When the user drags down while already near the bottom,
    // userScrolledAway never flips true, so without this grace window the
    // three stages all run on the next chunk and the user sees the chat
    // "jump back" three times. 1 s covers a typical fling settle (~500-
    // 800 ms) plus a small buffer; the user can re-engage follow at any
    // time by scrolling all the way to the bottom (LE(isNearBottom) above
    // resets userScrolledAway).
    var lastInterruptMs by remember { mutableStateOf(0L) }
    // [T-android-composer-input-blocked-while-streaming] True only while the
    // user's FINGER is actively dragging the message list. Programmatic scrolls
    // (the streaming auto-follow glide, settle, pin-to-bottom) go through
    // `listState.scroll { }` / `scrollToItem`, which set
    // `listState.isScrollInProgress = true` but emit NO DragInteraction. So a
    // gesture-only signal lets us distinguish "user scrolled the transcript"
    // (should dismiss the keyboard) from "streaming auto-followed" (must NOT
    // touch focus). Without this the keyboard closed itself mid-stream and
    // dropped the in-flight keystroke (the reported "can't type while
    // streaming" bug).
    var isUserDragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // T-android-jank-profile: drag interactions fire on every drag
            // event during a scroll (Press / Cancel / Stop). String-building
            // logs here added measurable load. Gate behind a constant.
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start ->
                    isUserDragging = true
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                    isUserDragging = false
                    lastInterruptMs = System.currentTimeMillis()
                    val nowAtBottom = isNearBottom.value
                    val newScrolledAway = !nowAtBottom
                    if (newScrolledAway != userScrolledAway) {
                        userScrolledAway = newScrolledAway
                    }
                }
                is androidx.compose.foundation.interaction.DragInteraction.Cancel ->
                    isUserDragging = false
                else -> Unit
            }
        }
    }
    // Re-engage follow whenever the user (manually or via FAB) returns
    // the viewport to the bottom.
    //
    // [T-android-stream-end-anchor-jump] Gate the clear on RECENT USER
    // INTERACTION. Without this, the final markdown re-render at the
    // streaming→idle edge briefly collapses the last assistant row's height
    // (large code blocks / tables / images finalizing their layout), which
    // clamps firstVisibleItemScrollOffset within the nearBottomThreshold for
    // one frame. isNearBottom flips true, this LE clears userScrolledAway,
    // and the stream-end settle LE — whose live-anchor check sees the same
    // bogus near-bottom offset — then scrollToItem()s the user back to
    // wherever the layout collapse parked them (often the start of the
    // current user message, because that's the "first content row" the
    // settle LE pins on). Only accept the clear when the user actually
    // dragged into this position recently — a real return-to-bottom gesture
    // always trails a DragInteraction.Stop, which lastInterruptMs records.
    // FAB taps also work because the FAB onClick path resets userScrolledAway
    // directly (line 1124) and never relies on this LE.
    LaunchedEffect(isNearBottom.value) {
        if (isNearBottom.value && userScrolledAway) {
            val sinceDragMs = System.currentTimeMillis() - lastInterruptMs
            // KEEP userScrolledAway when no recent drag — the at-bottom
            // reading came from a stream-end layout reflow, not a real
            // return-to-bottom gesture.
            if (sinceDragMs > 1500L) return@LaunchedEffect
            userScrolledAway = false
        }
    }
    // T169 / T170: an IME show/hide animates the LazyColumn's content area,
    // which can briefly register as a synthetic drag-stop and flip
    // userScrolledAway=true even though the user never actually scrolled.
    //
    // T170: only force-reset when we're actually back at the bottom. Earlier
    // behaviour of unconditionally clearing userScrolledAway hid the FAB on
    // users who had scrolled up to read history and then opened the keyboard
    // to send a follow-up — auto-follow then yanked them away from where
    // they were reading.
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        if (userScrolledAway && isNearBottom.value) userScrolledAway = false
    }
    // [T-android-tool-autoscroll] Start-of-turn edge from ViewModel: resume() /
    // retryLast() / retryFromMessage() / rerunFromToolBlock() emit Unit on
    // forceScrollToBottom because they don't append a new user-message row, so
    // LE(messages.size) below skips them. Without this collector the "Minis is
    // thinking…" placeholder stays parked behind the input bar until the first
    // streamed token finally bumps the auto-follow tuple.
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            // Match the user-send path: clear any prior "scrolled away" flag
            // so the streaming auto-follow stays active for the new turn.
            userScrolledAway = false
            tracedScrollToItem("FORCE-SCROLL-TO-BOTTOM(resume/retry/rerun)", 0, 0)
        }
    }
    // Auto-scroll on user-send: explicit "show me the next response"
    // gesture, fires regardless of current scroll position.
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull() ?: return@LaunchedEffect
        if (lastMsg.role != "user") return@LaunchedEffect
        // T255: catch-all reset so any user-message append path
        // (send button / Enter / enqueue-while-streaming / future
        // entry points) restores auto-follow even if a call-site reset
        // was missed.
        lastUserAppendMs = System.currentTimeMillis()
        userScrolledAway = false
        tracedScrollToItem("LE(messages.size)USER-SEND-SNAP", 0, 0)
    }
    // T128: streaming auto-follow when the user is at the bottom.
    //
    // T120 removed all per-token scroll calls assuming reverseLayout
    // would keep the bottom pinned natively. That's true for *new
    // LazyList items*, but a streaming text block grows by appending
    // characters into the same index-0 message item — its height
    // increases while LazyListState keeps firstVisibleItemIndex=0
    // and offset=0, so the new tokens push out below the viewport
    // (and behind the floating tool thumbnail). Result: users at the
    // bottom watched the FAB pop up, tapped it, and immediately had
    // to tap again as the next chunk landed.
    //
    // T170: align with iOS three-stage pin (initial scroll → wait for
    // layoutIfNeeded → re-pin to catch async self-sizing). After
    // scrollToItem(0) we await one frame then re-pin, which catches the
    // common case of a tool-pill + typing indicator inserted in the same
    // recomposition: the first scroll pins to the pre-grow position, the
    // second pin captures the post-self-sizing height. Suppressed when
    // the user is currently dragging — never compete with active touch.
    // T256: streaming auto-follow via snapshotFlow + conflate + sample.
    // Replaces the per-token `LaunchedEffect(lastAssistantStreamingKey)`
    // that pegged the Pixel 4a UI thread (95p frame 77ms / 29% janky) by
    // restarting the entire 3-stage scroll dance on every token. The new
    // pipeline:
    //   1. snapshotFlow emits a tuple per recomposition rather than the
    //      raw content string — content-length comparison is cheap.
    //   2. conflate() drops intermediate ticks the collector never saw.
    //   3. sample(150L) caps follow rate to ~6.5 Hz, matching iOS's
    //      80ms scroll-coalesce + 100ms layout-flush combined gate.
    // Stage 2 (frame settle) and stage 3 (220ms offset clip) move into a
    // separate edge-triggered LE that fires once per stream END, not per
    // token — async self-sizing / image height settling needs the safety
    // net but not at 50ms cadence.
    LaunchedEffect(listState, userScrolledAway) {
        // T-streaming-side-channel: combine the canonical messages flow
        // with streamingById so growth signals (content length, toolBlocks
        // count, awaiting flag) reflect the live stream — otherwise the
        // auto-follow scroll-to-bottom stops firing during a turn because
        // messages no longer ticks per token.
        kotlinx.coroutines.flow.combine(
            snapshotFlow { messages },
            viewModel.streamingById,
        ) { msgs, stream ->
            val effective = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
            val m = effective.lastOrNull { it.role == "assistant" } ?: return@combine null
            // [T-android-tool-autoscroll] Trigger tuple includes a per-block
            // signature (FNV-1a hash over id/kind/status/length) so the
            // collector wakes on RUNNING→SUCCESS transitions, new-block
            // appearances, kind flips, and per-token content growth alike.
            // Without blockSig the previous (lastIdx, growth, size, awaiting)
            // tuple missed several mid-loop transitions and the auto-follow
            // skipped scroll ticks during tool swaps.
            var growth: Long = m.content.length.toLong()
            var blockSig: Long = 1469598103934665603L // FNV-1a 64-bit offset basis
            for (b in m.toolBlocks) {
                growth += b.content.length.toLong()
                blockSig = blockSig xor b.id.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.kind.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor (b.toolStatus?.ordinal?.toLong() ?: -1L)
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.content.length.toLong()
                blockSig *= 1099511628211L
            }
            ScrollFollowKey(
                lastIndex = effective.lastIndex,
                growth = growth,
                toolBlockCount = m.toolBlocks.size,
                awaiting = m.isAwaitingModelResponse,
                blockSig = blockSig,
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            // [T-android-stream-grow-anim] Follow the bottom often enough that
            // the viewport never falls more than a fraction of one item behind.
            // Diagnostics with a 350ms sample showed GLIDE starting from
            // fIdx=1..5 — the viewport was whole items behind, and
            // animateScrollToItem across multiple items snaps most of the
            // distance instantly then animates only the last sliver, so it
            // read as "no animation". With the VM-side dual-path flush already
            // pacing content updates to 200–500ms, a 120ms scroll sample keeps
            // the viewport within the SAME item (fIdx=0, small fOff), where
            // animateScrollToItem is a genuine smooth glide. (The "accumulate
            // then glide" the user asked for now lives in the VM flush; here we
            // just keep up smoothly.)
            .sample(120L)
            .collect {
                if (!viewModel.isStreaming.value) return@collect
                if (userScrolledAway) return@collect
                if (listState.isScrollInProgress) return@collect
                val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                if (sinceInterrupt < 1000L) return@collect
                // [P0-0-jump-fix] When content insertion pushes the viewport
                // from index 0 to 1+ (new typing/tool row), the glide would
                // drag the user back to the newest row — same root cause as
                // the trailing-row pin yank. The trailing-row pin (T304)
                // handles new-item insertion separately; the glide should only
                // handle drift within the same item (firstIdx=0, firstOff>0).
                if (listState.firstVisibleItemIndex > 0) return@collect
                // [T-android-stream-grow-anim] Frame-driven glide to the bottom.
                // animateScrollToItem(0) was the problem: when the viewport had
                // fallen >= 1 item behind (diagnostics showed fIdx=1..5 during
                // fast streams), it snaps most of the distance instantly and
                // animates only the final sliver — reading as "no animation".
                // Instead, scroll toward the bottom a bounded amount per frame
                // inside one scroll session until index 0 is fully pinned
                // (fIdx==0 && fOff==0). Every frame moves, so the whole catch-up
                // is visibly animated regardless of how many items behind we
                // are. We never measure item heights (the source of earlier
                // stutter) — we just step toward the bottom and stop when the
                // pin condition is met. In reverseLayout, the bottom (newest,
                // index 0) is the NEGATIVE scroll direction.
                if (listState.firstVisibleItemScrollOffset != 0) {
                    // [T-android-stream-grow-anim review] Cold start: item sizes
                    // not measured yet → avgItemSize==0 → the distance estimate
                    // is bogus and the glide would under-scroll. Snap instead;
                    // by the next sample the cache is warm and glides resume.
                    if (avgItemSize.value <= 0) {
                        tracedScrollToItem("LE(streaming-content)cold", 0, 0)
                        return@collect
                    }
                    // [T-android-stream-grow-anim] Ease-out frame-driven glide
                    // to the bottom. Each frame moves a fraction of the
                    // estimated remaining distance so the motion decelerates as
                    // it lands (curveEaseOut, the shape iOS uses for its 0.2s
                    // contentOffset animate). Two caps keep it smooth on the
                    // matched matters:
                    //   • per-frame step is capped well BELOW a typical
                    //     streaming fragment (~tens of px) so a single frame
                    //     can never leap a whole item — that leap was the
                    //     residual "frames=1 jump" in earlier diagnostics
                    //     (avg-estimated remaining over-shot, step hit the cap,
                    //     one frame crossed an item).
                    //   • a gentler 0.22 fraction + 14px floor stretches even a
                    //     short catch-up across several frames, so it always
                    //     reads as a glide rather than a hop.
                    // Distance uses the running average visible-item height (an
                    // aggregate, not a per-item delta, so no re-block noise).
                    val avg = avgItemSize.value.toFloat().coerceAtLeast(1f)
                    // Step ceiling: ~40% of the average item, so >= ~3 frames
                    // cross any one item. Bounded to a sane absolute window.
                    val stepCeil = (avg * 0.40f).coerceIn(28f, 80f)
                    runCatching {
                        listState.scroll {
                            var guard = 0
                            while (
                                (listState.firstVisibleItemIndex != 0 ||
                                    listState.firstVisibleItemScrollOffset != 0) &&
                                guard < 120
                            ) {
                                guard++
                                val remaining = listState.firstVisibleItemIndex * avg +
                                    listState.firstVisibleItemScrollOffset
                                val step = (remaining * 0.22f).coerceIn(14f, stepCeil)
                                // Negative = toward newest/bottom in reverseLayout.
                                val consumed = withFrameNanos { scrollBy(-step) }
                                if (consumed == 0f) break
                            }
                        }
                    }
                }
            }
    }
    // T256: stage-2/3 settle moved out of the per-token LE — runs once on
    // the stream-end edge (isAwaitingModelResponse stays false but the
    // assistant message growth rate drops to zero). Catches async
    // self-sizing of code blocks / tables / images that finish layout
    // beyond the last sample tick.
    // T-streaming-side-channel: derive streamingNowFlag from isStreaming
    // VM flow directly (turn-level signal) rather than reading per-token
    // assistant content, so this state doesn't tick during a stream.
    val streamingNowFlag = isStreaming
    LaunchedEffect(streamingNowFlag) {
        if (streamingNowFlag) return@LaunchedEffect  // edge fires only on streaming→idle
        // [T-android-stream-end-reflow-flicker-v18] The user-was-reading
        // branch is a HARD NO-OP. After the v18 ChatFlatItems fix (keep
        // rawFragments on the last assistant turn regardless of
        // isStreaming), the mdblock key set no longer changes across
        // stream-end, so LazyColumn's key reconciliation preserves the
        // user's visual position natively. Versions v6..v17 of this LE
        // tried every cross-key restore strategy (exact, fuzzy, neighbor,
        // numeric, totalItems-delta) and every one made the symptom
        // worse — see commits cd08a334 and 9873a3ed for the analysis.
        kotlinx.coroutines.delay(220)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        // [P0-0-jump-fix] Same guard as trailing-row pin: content insertion
        // can push the viewport from index 0 to 1 without user drag, leaving
        // userScrolledAway=false. Without isNearBottom the re-pin yanks the
        // user to the newest row even though they are reading pushed-up content.
        if (!isNearBottom.value) return@LaunchedEffect
        val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
        if (sinceInterrupt < 1000L) return@LaunchedEffect
        // At-bottom settle: user was following the stream; re-pin to
        // index 0 so async self-sizing (code blocks / tables / images)
        // finishing after our last per-token scrollToItem still leaves
        // the bottom item flush with the viewport bottom.
        tracedScrollToItem("stream-end/AT-BOTTOM-RE-PIN", 0, 0)
        withFrameNanos { }
        kotlinx.coroutines.delay(900)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        // [P0-0-jump-fix] Re-check isNearBottom: content insertion during the
        // 900ms settle window can push the viewport from index 0 to 1, and
        // the LATE-REPIN would yank the user back to the newest row.
        if (!isNearBottom.value) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0) {
            val sinceDrag = System.currentTimeMillis() - lastInterruptMs
            if (sinceDrag > 1500L) tracedScrollToItem("stream-end/LATE-REPIN", 0, 0)
        }
    }
    // T170: layoutInfo-driven safety net. iOS pins via contentSize KVO on
    // every height change; on Compose the equivalent is a snapshotFlow over
    // layoutInfo. This catches:
    //   - Async self-sizing that completes >1 frame after the streaming LE
    //   - StreamingMarkdownText reflow when its width changes (orientation,
    //     IME pad)
    //   - Tool-block height changes (e.g. terminal preview expanding)
    // Triggers ONLY when the bottom item is visible AND the user hasn't
    // scrolled away — otherwise we'd fight the user's manual scroll.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            // Track (bottom-item-size, total-content-size, viewport-end) so
            // any height change re-fires.
            Triple(
                bottomItem?.size ?: -1,
                info.visibleItemsInfo.sumOf { it.size },
                info.viewportEndOffset,
            )
        }.distinctUntilChanged().collectLatest { _ ->
            if (userScrolledAway) return@collectLatest
            if (listState.isScrollInProgress) return@collectLatest
            if (!isNearBottom.value) return@collectLatest
            // Pin only if the current offset has actually drifted from
            // the bottom (avoid no-op scroll spam).
            val info = listState.layoutInfo
            val bottomItemNow = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            // T180/T181: in reverseLayout=true, the bottom item (index 0,
            // newest) anchors to viewport bottom when its offset == 0.
            // When NEW items are inserted at index 0 and the LazyList does
            // NOT auto-shift older items, the bottom item ends up below
            // the viewport (negative offset). That's the only drift we
            // need to fix. Don't react to gap<0 — for a tall bottom item
            // that's the correct anchored-at-bottom state.
            val bottomOffsetNow = bottomItemNow?.offset ?: 0
            if (bottomOffsetNow >= 0) return@collectLatest
            tracedScrollToItem("LAYOUT-DRIFT-SNAP", 0, 0)
            val infoAfter = listState.layoutInfo
            val bottomNow = infoAfter.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (bottomNow != null && bottomNow.offset < 0) {
                tracedScrollBy("LAYOUT-DRIFT-CLIP", bottomNow.offset.toFloat())
            }
        }
    }
    // T170: when a user-initiated drag ends near the bottom, give the
    // streaming follow path one extra pin in case content grew during the
    // drag (we suppressed the streaming LE while isScrollInProgress). This
    // mirrors iOS settleAfterInteraction.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                // [T-android-small-drag-snaps-back] Re-pin to bottom after a
                // drag-end ONLY while actively STREAMING. T170's purpose is to
                // catch content that grew during a drag we suppressed the
                // streaming-follow LE for — that only matters mid-stream. When
                // NOT streaming, a finger lift near the bottom must NOT yank the
                // viewport back: that was the "small upward drag snaps back to
                // bottom" bug (logged: settle-after-interaction firing with
                // firstOff=27/34 right after a sub-threshold peek). The earlier
                // bottom-item-offset<0 guard could not distinguish a user's
                // upward peek from content drift — in reverseLayout BOTH push the
                // bottom item's offset negative — so it never actually gated.
                // isStreaming is the real discriminator. The streaming-content LE
                // (gated by lastInterruptMs + isScrollInProgress) handles the
                // follow itself; this is just the post-drag settle for it.
                if (!inProgress && !userScrolledAway && isNearBottom.value &&
                    viewModel.isStreaming.value
                ) {
                    tracedScrollToItem("settle-after-interaction", 0, 0)
                }
                // [T-android-scroll-fling-stale-userscrolledaway #49] Catch
                // the stale-userScrolledAway window left by a fling-from-
                // bottom: DragInteraction.Stop fires at finger lift while
                // isNearBottom is still true, so the DragStop handler sets
                // userScrolledAway=false; the fling then carries the
                // viewport off-bottom. Use the isScrollInProgress→false
                // edge (past drag AND fling settle) as the authoritative
                // checkpoint and re-arm userScrolledAway.
                if (!inProgress && !isNearBottom.value && !userScrolledAway) {
                    userScrolledAway = true
                }
            }
    }

    // [T-android-no-auto-focus] Entering the app / opening a new chat no
    // longer auto-focuses the composer, so the keyboard stays hidden until
    // the user actually taps the input field (RikkaHub-style: no implicit
    // focus requests anywhere). The old block below requested focus 300 ms
    // after mounting any "__new__" draft session, which popped the keyboard
    // on every cold start.
    //
    // T176 note (kept for history): theme switch (Activity recreate) used to
    // re-enter that LaunchedEffect before the composer's focusRequester was
    // attached, so requestFocus() threw `FocusRequester is not initialized`.
    // With auto-focus removed the crash path is gone too.

    // Show top-level error in snackbar (only for errors without an assistant message)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // T-imgsize: surface composer-side image-budget actions (compress / drop)
    // via Snackbar. Each event is one user send; we emit at most two short
    // notices (compressed count + dropped count) so the user understands
    // why we touched their attachments before the provider would 413.
    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.compressedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_compressed, ev.compressedCount),
                )
            }
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_total_exceeded),
                )
            }
        }
    }

    // T-request-imgsize: surface request-level image-budget elisions
    // (older images compacted into text placeholders to fit the 25MB
    // request cap). Independent flow from the composer-side budget so
    // both can fire on the same turn without racing.
    LaunchedEffect(Unit) {
        viewModel.requestBudgetEvent.collect { plan ->
            if (plan.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.image_budget_request_elided,
                        plan.droppedCount,
                    ),
                )
            }
        }
    }

    val appearancePrefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)) }
    var toolPreviewEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, false)) }
    // T-chat-title-pill: live-toggled by Settings → Appearance and by
    // `minis-config set appearance.show_chat_title …`. Default ON.
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }
    // T-chat-title-pill-edit: state for the in-chat edit-title sheet (the
    // exact same SessionEditSheet hosted by the session list home screen,
    // reused via `internal` visibility — no duplicate UI). Populated by an
    // async repo lookup once the user taps the title pill.
    var editingSession by remember { mutableStateOf<com.openminis.app.data.db.ChatSessionEntity?>(null) }
    DisposableEffect(appearancePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                com.openminis.app.ui.settings.KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_TOOL_PREVIEW -> toolPreviewEnabled = sp.getBoolean(key, false)
                com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val markdownFontScale = com.openminis.app.ui.settings.fontScaleForLevel(messageFontLevel)
    val chatInputFontScale = com.openminis.app.ui.settings.fontScaleForLevel(chatInputLevel)

    var previewUrl by remember { mutableStateOf<String?>(null) }
    // T146: dedicated state for the immersive HTML preview path. Holding
    // both a `holder` and `fullscreen` flag (rather than two separate
    // states) ensures the same WebView survives the sheet→fullscreen
    // toggle without reloading the page (iOS parity, WebPreviewSheet.swift).
    var htmlPreviewHolder by remember {
        mutableStateOf<com.openminis.app.ui.preview.WebViewHolder?>(null)
    }
    var htmlPreviewFallbackTitle by remember { mutableStateOf("") }
    var htmlPreviewFullscreen by remember { mutableStateOf(false) }
    val appCtx = context.applicationContext
    val openHtmlPreview = remember<(java.io.File, String) -> Unit>(appCtx) {
        { file, title ->
            // Reuse the same WebViewHolder as long as the file path doesn't
            // change. Tapping the same html link twice should pick up wherever
            // the user left off rather than reloading from scratch.
            val url = "file://${file.absolutePath}"
            val existing = htmlPreviewHolder
            if (existing == null || existing.currentUrl != url) {
                existing?.destroy()
                htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
            }
            htmlPreviewFallbackTitle = title
            htmlPreviewFullscreen = false
        }
    }
    // Pinned-shortcut deep link: minis://session/<id>/<resource-path>
    // consumes here on first composition iff this screen is showing the
    // matching session; opens fullscreen HTML preview backed by a fresh
    // holder. Pending state is left untouched when a different chat is on
    // screen so the right ChatScreen instance still consumes it later.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingHtmlPreview.value ?: return@LaunchedEffect
        if (pending.sessionId != sessionId) return@LaunchedEffect
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        val absPath = "/var/minis" + pending.resourcePath
        val file = java.io.File(absPath)
        if (!file.exists()) {
            com.openminis.app.logging.AppLogger.warning(
                "ChatScreen",
                "pinned HTML preview path missing: $absPath",
            )
            return@LaunchedEffect
        }
        val url = "file://${file.absolutePath}"
        htmlPreviewHolder?.destroy()
        htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
        htmlPreviewFallbackTitle = pending.title
        htmlPreviewFullscreen = true
    }
    // T-imgswipe-4f446d83: replace previous single-image preview state with a
    // gallery (list + start index) so callers can pass sibling images (input
    // chip row, message attachments, file-browser dir contents). Single-image
    // taps still work — they pass a 1-item list.
    var previewImageGallery by remember {
        mutableStateOf<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>(null)
    }
    // Video links from chat go through MinisFullscreenVideoPlayer rather than
    // FilePreviewScreen → InlineVideoPlayer. The inline player wraps a bare
    // VideoView with an anchored MediaController and never starts playback,
    // so a tap on an mp4 link rendered as a black surface until the user
    // happened to tap again to surface the controller. The fullscreen player
    // auto-starts on prepared, has a built-in scrubber + play/pause, and an
    // onError listener so failures actually log instead of silently blanking.
    var previewVideoFile by remember { mutableStateOf<java.io.File?>(null) }
    // T-pwa-2: long-press on an HTML attachment chip opens the
    // "Add to Home Screen" sheet for that attachment.
    var webAppSheetTarget by remember { mutableStateOf<InputAttachment?>(null) }
    val urlClickHandler = remember<(String) -> Unit>(viewModel) {
        { url ->
            // Pass the current session id so `minis://attachments/...` resolves
            // against this chat's session directory rather than whichever
            // session booted its PRoot shell most recently (which is what
            // the global bindMounts map would answer).
            when (val action = ChatLinkResolver.resolve(url, viewModel.currentSessionId, context)) {
                is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                is ChatLinkAction.SandboxFile -> {
                    when {
                        action.item.isImageFile -> {
                            // Single image — caption = filename. Sibling
                            // collection from markdown context is not
                            // plumbed here (iOS does cross-session
                            // assistant images via fingerprint).
                            previewImageGallery = listOf(
                                com.openminis.app.ui.components.ImageGalleryItem(
                                    model = action.item.file,
                                    caption = action.item.name,
                                ),
                            ) to 0
                        }
                        action.item.isVideoFile -> previewVideoFile = action.item.file
                        // T146: HTML files take the immersive web-preview path
                        // (iOS-style 90% bottom sheet + fullscreen toggle)
                        // instead of FilePreviewScreen's plain fullscreen
                        // Scaffold. snake_game.html and similar generated
                        // pages need browser controls to feel right.
                        action.item.isHtmlFile -> openHtmlPreview(action.item.file, action.item.name)
                        // T279: route through the NavHost FILE_PREVIEW destination
                        // (same path as user-bubble attachments and "Browse Chat Files")
                        // so FilePreviewScreen inherits the Activity's edge-to-edge
                        // window setup. The previous in-place Dialog wrapper had
                        // its own Window without enableEdgeToEdge, painting the
                        // platform default scrim on the status / nav bars.
                        else -> onPreviewAttachment(action.item)
                    }
                }
                is ChatLinkAction.ExternalApp ->
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .handle(context, action.url)
                is ChatLinkAction.Web -> previewUrl = action.url
            }
        }
    }

    // Auto-present the in-app preview when a shell tool's stdout emits an
    // OSC MinisOpenURL marker (via /usr/local/bin/minis-open). The broker is
    // populated by ChatViewModel's shell lineCallback; forwarding the URL
    // into `urlClickHandler` routes it exactly like a chat-link tap —
    // http(s)/about → UrlPreviewSheet, minis:// deep links → DeepLinkHandler,
    // minis://<host>/<path> → in-app file preview by extension.
    val pendingMinisOpenUrl by com.openminis.app.terminal.MinisOpenUrlBroker.pendingUrl
        .collectAsState()
    val minisOpenTerminalVisible by com.openminis.app.terminal.MinisOpenUrlBroker.terminalVisible
        .collectAsState()
    LaunchedEffect(pendingMinisOpenUrl, minisOpenTerminalVisible) {
        val url = pendingMinisOpenUrl ?: return@LaunchedEffect
        // The fullscreen TerminalScreen owns the broker while it's up —
        // let it present its own web preview (mirrors iOS ISHTerminalView)
        // so we don't try to open a sheet on a covered ChatScreen.
        if (minisOpenTerminalVisible) return@LaunchedEffect
        urlClickHandler(url.toString())
        com.openminis.app.terminal.MinisOpenUrlBroker.consume()
    }

    // [T-android-markdown-image-gallery-cross-message] Collect every
    // `![alt](src)` markdown image emitted by any assistant message in the
    // current windowed view, in chronological order, then open the paged
    // ImageGalleryViewer positioned at the tapped image. Mirrors iOS
    // AIChatView.handleMarkdownImageTap (AIChatView.swift:2082). The regex
    // matches the standard inline image form; tool-block content stays
    // untouched (toolBlocks live in a separate AssistantBlock list, not
    // in `content`). Video/audio extensions are filtered out so the gallery
    // only contains still images. Resolution of `minis://` → host File is
    // deferred to the gallery's Coil model — Coil's MinisImageFetcher walks
    // the same session-aware resolver we use for inline rendering.
    val markdownImageTapHandler = remember<(String, String) -> Unit>(messages, sessionId) {
        handler@{ tappedMessageId, tappedUrl ->
            val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
            data class Ref(val messageId: String, val source: String, val title: String)
            val refs = mutableListOf<Ref>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                val content = msg.content
                if (content.isEmpty()) continue
                for (m in imageRegex.findAll(content)) {
                    val alt = m.groupValues.getOrNull(1).orEmpty()
                    val src = m.groupValues.getOrNull(2).orEmpty()
                    if (src.isEmpty()) continue
                    val pathPart = src.substringBefore('?').substringBefore('#')
                    val ext = pathPart.substringAfterLast('.', "").lowercase()
                    // Skip non-image media so the gallery stays still-image only,
                    // matching iOS minisVideoExtensions / minisAudioExtensions.
                    if (ext in setOf("mp4", "mov", "avi", "mkv", "webm",
                                     "mp3", "wav", "aac", "flac", "ogg", "m4a")) continue
                    val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
                    refs.add(Ref(msg.id, src, title))
                }
            }
            if (refs.isEmpty()) {
                // Defensive: tap arrived for a URL that isn't in the visible
                // window (compacted away, just deleted, etc.). Fall back to
                // the single-item URL handler so the user still sees the
                // tapped image rather than swallowing the tap silently.
                urlClickHandler(tappedUrl)
                return@handler
            }
            val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
                .takeIf { it >= 0 }
                ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
                ?: 0
            val items = refs.map { ref ->
                // Resolve minis://... / file:// / /abs → host File so Coil
                // doesn't have to re-walk PRootKernel for every page swipe.
                // Falls back to the raw URL string when resolution misses —
                // AsyncImage will route it through MinisImageFetcher anyway.
                val resolved = resolveMdMediaFile(context, ref.source, sessionId)
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = resolved ?: ref.source,
                    caption = ref.title,
                )
            }
            previewImageGallery = items to startIndex
        }
    }

    CompositionLocalProvider(
        LocalBrowserTabPool provides viewModel.browserTabPool,
        LocalMarkdownFontScale provides markdownFontScale,
        LocalToolPreviewEnabled provides toolPreviewEnabled,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        // Route markdown media resolution through this chat's session so
        // minis://attachments/* lookups don't rely on the global bindMounts
        // map (which is last-writer-wins across sessions).
        LocalMarkdownSessionId provides sessionId,
    ) {
    // RikkaHub-style left-swipe drawer: the chat is wrapped in a
    // ModalNavigationDrawer whose sheet is the conversation history. A left
    // edge-swipe (or the hamburger) opens it; tapping a session, New Chat, or
    // Settings navigates and closes it. gesturesEnabled is on so the swipe
    // works anywhere the chat body would otherwise not consume a horizontal
    // drag.
    val historyDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    // Reuse the screen's existing coroutineScope (declared above) to drive
    // open/close animations.
    val historyDrawerScope = coroutineScope
    // Close the drawer with system back before it falls through to onBack.
    androidx.activity.compose.BackHandler(enabled = historyDrawerState.isOpen) {
        historyDrawerScope.launch { historyDrawerState.close() }
    }
    // [composer-draft-v1/ime] Dismiss the IME the moment the history drawer
    // starts opening (targetValue, not isOpen) so the sheet is never covered
    // by the keyboard: the drawer sheet sits OUTSIDE the chat's imePadding(),
    // so an open keyboard would otherwise overlap its bottom rows (session
    // entries + footer buttons).
    LaunchedEffect(historyDrawerState) {
        snapshotFlow { historyDrawerState.targetValue }
            .distinctUntilChanged()
            .collect { target ->
                if (target == DrawerValue.Open) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }
    }
    // [bottom-toolbar-customizable] Footer actions resolved from the pin order,
    // filtered by runtime availability so a hidden Skills/MCPs/Memory
    // repository never leaves a dead button in the footer.
    val footerSpecs = remember(chatActions.footerOrder, skillRepository, mcpRepository, memoryRepository, menuMemoryEnabled) {
        chatActions.footerOrder.mapNotNull { key ->
            if (!isChatActionAvailable(
                    key,
                    skillsAvailable = skillRepository != null,
                    mcpsAvailable = mcpRepository != null,
                    memoryAvailable = memoryRepository != null && menuMemoryEnabled,
                )
            ) {
                null
            } else {
                ChatActionCatalog.spec(key)
            }
        }
    }

    // [bottom-toolbar-customizable] Footer taps dispatch directly via
    // historyDrawerScope. We deliberately do NOT drive them through a keyed
    // LaunchedEffect: a footer tap that sets a state key and then suspends on
    // historyDrawerState.close() would be the LaunchedEffect's own key, so the
    // effect would cancel its own coroutine the moment that state is written,
    // killing close() and dropping the dispatch (footer buttons appeared to
    // "do nothing"). Launching into historyDrawerScope (the un-keyed, shared
    // scope, same as onNewChat / onOpenDraft) keeps the launch alive across
    // recomposition; suspending close() inside it also makes sure the slash-menu
    // focus restore runs only after the drawer fully closed.
    ModalNavigationDrawer(
        drawerState = historyDrawerState,
        gesturesEnabled = true,
        drawerContent = {
            // [composer-draft-v1] Live snapshot of the persisted draft slot,
            // surfaced as a "Draft" row at the top of the history drawer.
            val draftSnapshot by com.openminis.app.data.ComposerDraftStore
                .observeDraftSnapshot(context)
                .collectAsState()
            ChatHistoryDrawer(
                chatRepository = chatRepository,
                currentSessionId = sessionId,
                draft = draftSnapshot
                    ?.takeIf { it.text.isNotBlank() && it.id != sessionId },
                onOpenDraft = {
                    historyDrawerScope.launch { historyDrawerState.close() }
                    draftSnapshot?.let { if (it.id != sessionId) onOpenSession(it.id) }
                },
                onDiscardDraft = {
                    draftSnapshot?.let { com.openminis.app.data.ComposerDraftStore.clearDraft(context, it.id) }
                },
                onSessionClick = { id ->
                    historyDrawerScope.launch { historyDrawerState.close() }
                    if (id != sessionId) onOpenSession(id)
                },
                onNewChat = {
                    historyDrawerScope.launch { historyDrawerState.close() }
                    onNewChat()
                },
                // [bottom-toolbar-customizable] Footer: resolved action list +
                // single dispatcher. The drawer no longer owns Settings/Token
                // Usage semantics — the caller (dispatchChatAction) does, so
                // footer taps behave exactly like their "..." menu twins.
                //
                // dispatch order mirrors the pre-refactor drawer (onSettings /
                // onTokenUsage): fire the action immediately and let the drawer
                // close in a parallel launch. Suspending close()-then-dispatch
                // was NOT equivalent and broke footer taps on device — a
                // ModalNavigationDrawer AnchoredDraggable close() can suspend
                // until the sheet settles, and the trailing dispatch then never
                // runs (footer buttons "did nothing"). Only SLASH_COMMANDS
                // genuinely needs the drawer closed first (it re-focuses the
                // composer underneath), so it alone waits on close().
                footerActions = footerSpecs,
                onAction = { key ->
                    if (key == ChatMenuPrefs.SLASH_COMMANDS) {
                        historyDrawerScope.launch {
                            historyDrawerState.close()
                            dispatchChatAction(key)
                        }
                    } else {
                        historyDrawerScope.launch { historyDrawerState.close() }
                        dispatchChatAction(key)
                    }
                },
            )
        },
    ) {
    Scaffold(
        containerColor = ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    // RikkaHub-style LEFT-aligned nav title, sitting directly
                    // beside the hamburger instead of floating in the centre.
                    // Previously this was centre-aligned with 32dp horizontal
                    // padding, which left the title visually detached from both
                    // the nav icon and the overflow button. Left alignment gives
                    // the title the full remaining width (long session names
                    // truncate later) and reads as "this drawer -> this chat".
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val noFontPad = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        )
                        // Fallback pulse animation (iOS: 3× red pulse on model switch)
                        val fallbackTrigger by viewModel.fallbackTrigger.collectAsState()
                        val fallbackPulseAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(fallbackTrigger) {
                            if (fallbackTrigger == 0) return@LaunchedEffect
                            repeat(3) {
                                fallbackPulseAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(350))
                                fallbackPulseAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(350))
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Red.copy(alpha = 0.35f * fallbackPulseAlpha.value))
                                // [T-android-topbar-shrink] vertical 4dp→2dp
                                // closes the dead-space gap between the model
                                // row and the TopAppBar bottom edge.
                                //
                                // Horizontal was 32dp to pad the fallback pulse
                                // around a centred title; now that the block is
                                // left-aligned that much inset would push the
                                // title away from the hamburger, so it drops to
                                // 4dp — the pulse highlight still has room
                                // because the Column no longer spans the full
                                // width.
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            // Nav title: current session title when one
                            // exists and the toggle is on, else the app name
                            // ("RikkaMinis"). A fresh draft shows the app
                            // name — the Soul name stays in the input
                            // placeholder, where it belongs; app_name was
                            // chosen over the Soul name so the top bar reads
                            // as the app, not the assistant persona.
                            // Tap opens the same SessionEditSheet used from
                            // the session list — drafts return null from
                            // loadSessionEntity so the sheet stays closed.
                            val displayTitle = when {
                                showChatTitlePill
                                    && sessionTitle.isNotBlank()
                                    && sessionTitle != "New Chat" -> sessionTitle
                                else -> stringResource(R.string.app_name)
                            }
                            Text(
                                text = displayTitle,
                                fontSize = 16.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        coroutineScope.launch {
                                            editingSession = viewModel.loadSessionEntity()
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            // Model picker subtitle: green dot + group +
                            // provider/model. Tap opens the model picker —
                            // separated from the title above so tapping the
                            // title rows opens the rename sheet instead.
                            Column(
                                horizontalAlignment = Alignment.Start,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showModelPicker = true }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                // Line 1: green dot + group name + dropdown
                                // arrow (iOS: "● Default ⌄"). Only shown when a
                                // GROUP is the active binding. When the user
                                // picked a single concrete model instead
                                // (selectEntry → selectedGroupId = null), this
                                // row is hidden entirely rather than falling
                                // back to the default group name — the old
                                // fallback made every single-model selection
                                // read as "Default group", which looked like
                                // the picker wasn't switching at all. Line 2
                                // below still shows the concrete provider·model,
                                // so no information is lost.
                                if (selectedGroupId != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    if (modelName.isNotEmpty()) Color(0xFF34C759) else Color(0xFFFF9500),
                                                    CircleShape,
                                                ),
                                        )
                                        // selectedGroupName can still be briefly
                                        // empty in the window before loadSession
                                        // resolves the group; fall back to the
                                        // live config's name for selectedGroupId,
                                        // then to the badge string. This is the
                                        // group-resolve race only — it no longer
                                        // fires for single-model selections,
                                        // which don't enter this branch.
                                        val groupNameDisplay = selectedGroupName.ifEmpty {
                                            availableGroups.firstOrNull { it.id == selectedGroupId }?.name
                                                ?: stringResource(R.string.model_picker_default_badge)
                                        }
                                        Text(
                                            text = groupNameDisplay,
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            style = noFontPad,
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = ChatColors.tertiaryText,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                // Line 2: "provider · model" (iOS: "MiniMax ·
                                // MiniMax-M2.7") + the thinking-level badge laid
                                // out as a Row of two SEPARATE tappable siblings
                                // (mirrors iOS AIChatView row-2 HStack).
                                //
                                // [T-android-thinking-badge-navbar] Gesture
                                // separation: the whole subtitle Column above owns
                                // `clickable { showModelPicker = true }`, so a tap
                                // on the model text still opens the model picker.
                                // The badge declares its OWN `clickable` (see
                                // ThinkingLevelBadge), and in Compose the innermost
                                // clickable consumes the down/up events — so a tap
                                // that lands on the badge opens the thinking sheet
                                // and never bubbles up to the Column's model-picker
                                // handler. Two hit targets, zero gesture conflict,
                                // no pointerInput plumbing needed.
                                //
                                // Sizing: the model text takes `weight(1f, fill =
                                // false)` so it truncates first (Ellipsis) when the
                                // navbar is narrow; the badge has no weight, so it
                                // keeps its intrinsic width and always renders in
                                // full — the level label never gets clipped.
                                if (providerName.isNotEmpty() || modelName.isNotEmpty()) {
                                    val thinkingLevelBadgeState by viewModel.thinkingLevel.collectAsState()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        // [T-codex-fast-mode] ⚡ badge ahead of the
                                        // resolved model name — small orange circle
                                        // + white bolt, shown only while Fast Mode
                                        // is enabled AND the active model is
                                        // eligible (iOS 9e3c76ef row-3 placement,
                                        // 09944220 9pt sizing).
                                        val fastBadgeEligible by viewModel.showFastModeToggle.collectAsState()
                                        val fastBadgeOn by viewModel.fastModeEnabled.collectAsState()
                                        if (fastBadgeEligible && fastBadgeOn) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .background(Color(0xFFFF9500), CircleShape),
                                            ) {
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(9.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (providerName.isNotEmpty() && modelName.isNotEmpty()) {
                                                "$providerName · $modelName"
                                            } else {
                                                modelName.ifEmpty { providerName }
                                            },
                                            fontSize = 11.sp,
                                            lineHeight = 13.sp,
                                            color = ChatColors.tertiaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = noFontPad,
                                            // Yield first when space is tight; the
                                            // badge to the right stays intrinsic.
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        // Show the badge ONLY when the model can
                                        // think AND thinking is currently on:
                                        //   - availableThinkingLevels non-empty →
                                        //     the bound model actually supports
                                        //     thinking (no badge for models that
                                        //     can't reason);
                                        //   - level.isEnabled (≠ Off) → thinking is
                                        //     switched on right now.
                                        // When Off we render NOTHING (no greyed
                                        // placeholder): iOS found a grey "Off" pill
                                        // read as ambiguous ("is thinking on or
                                        // off?"), so the badge simply disappears.
                                        // The sheet still lists Off, so users can
                                        // turn thinking back off from there.
                                        if (viewModel.availableThinkingLevels.isNotEmpty() &&
                                            thinkingLevelBadgeState.isEnabled
                                        ) {
                                            ThinkingLevelBadge(
                                                level = thinkingLevelBadgeState,
                                                onClick = { showThinkingLevelSheet = true },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    // Hamburger opens the RikkaHub-style chat-history drawer
                    // (ModalNavigationDrawer wrapping this Scaffold). A left
                    // edge-swipe opens the same drawer. onBack is still used by
                    // system/predictive back to leave the chat; only the glyph
                    // and this tap target changed.
                    IconButton(onClick = {
                        historyDrawerScope.launch {
                            if (historyDrawerState.isOpen) historyDrawerState.close()
                            else historyDrawerState.open()
                        }
                    }) {
                        Icon(Icons.Default.Menu, contentDescription = "Sessions")
                    }
                },
                actions = {
                    // New Chat — promoted from the "..." menu to a persistent
                    // top-bar button beside "..." (iOS parity: square.and.pencil
                    // sits next to the overflow, one tap instead of two).
                    // Always creates the draft directly — even while streaming.
                    // Leaving the chat does NOT stop the running task: the VM
                    // lives in the process-level ChatViewModelStore, so the
                    // agent loop keeps streaming in the background and the
                    // finished response lands in this session's history (the
                    // sessions list shows a spinner meanwhile). This matches
                    // session switching via the drawer, which never confirms
                    // either; a "stop the task" prompt here would be both
                    // inconsistent and factually wrong.
                    IconButton(onClick = { onNewChat() }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.chat_menu_new_chat))
                    }
                    // [T-input-history] Always-visible history button (pinned
                    // like New Chat) — shows a searchable list of all messages
                    // in the current session so the user can jump back to any
                    // earlier input in one tap. Intentionally NOT inside the
                    // hasAnyMenuItems gate: both high-frequency actions (New
                    // Chat and Input History) are immune to the "hide empty
                    // menu" collapse that governs "...". However the user CAN
                    // opt-out via Settings → Appearance → Chat Menu.
                    if (chatActions.topBarInputHistoryVisible) {
                        IconButton(onClick = { showInputHistorySheet = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = stringResource(R.string.input_history_title),
                            )
                        }
                    }
                    // [T-chat-menu-empty] The "..." button is only worth
                    // rendering when it has at least one entry to show. If
                    // every customizable entry is off AND no model-conditional
                    // toggle (Enhanced Cache / Fast Mode) applies AND this
                    // isn't a DEBUG build, the menu would open empty — a dead
                    // control — so nothing renders and the New Chat pencil
                    // becomes the last action on the bar.
                    // [T-chat-menu-solo] When EXACTLY ONE entry would render,
                    // the overflow is pointless: promote it to a direct
                    // top-bar IconButton (one tap instead of two) and drop the
                    // "..." entirely. Two+ entries keep the overflow. The
                    // DEBUG crash trigger is counted but never promoted — a
                    // stray tap on a lone top-bar button would crash the app —
                    // so a menu that would contain only it keeps the "...".
                    // The gates below mirror the ones applied while rendering
                    // the items, so button visibility and menu content can
                    // never disagree.
                    val visibleCustomEntries = chatActions.menuOrder.filter { key ->
                        chatActions.isVisible(key) && isChatActionAvailable(
                            key,
                            skillsAvailable = skillRepository != null,
                            mcpsAvailable = mcpRepository != null,
                            memoryAvailable = memoryRepository != null && menuMemoryEnabled,
                        )
                    }
                    val showEnhancedCache by viewModel.showEnhancedCacheToggle.collectAsState()
                    val enhancedCacheOn by viewModel.enhancedCacheEnabled.collectAsState()
                    val showFastMode by viewModel.showFastModeToggle.collectAsState()
                    val fastModeOn by viewModel.fastModeEnabled.collectAsState()
                    val visibleMenuCount =
                        visibleCustomEntries.size +
                            (if (showEnhancedCache) 1 else 0) +
                            (if (showFastMode) 1 else 0) +
                            (if (BuildConfig.DEBUG) 1 else 0)
                    // Lone customizable entry (Terminal / Browser / Export / …)
                    // → direct button. Icon + label come from the same
                    // ChatActionCatalog the settings screen uses, so the
                    // promoted button always matches the entry it replaces.
                    val soloCustomKey =
                        if (visibleMenuCount == 1 && visibleCustomEntries.size == 1) visibleCustomEntries.first() else null
                    // Lone model-invocation toggle → direct button whose tint
                    // carries the on/off state (the trailing Switch would have
                    // nowhere to live).
                    val soloCacheToggle = visibleMenuCount == 1 && visibleCustomEntries.isEmpty() && showEnhancedCache
                    val soloFastToggle = visibleMenuCount == 1 && visibleCustomEntries.isEmpty() && showFastMode
                    val hasAnyMenuItems =
                        visibleMenuCount >= 1 && soloCustomKey == null && !soloCacheToggle && !soloFastToggle
                    // iOS: "..." circle button → dropdown menu
                    if (soloCustomKey != null) {
                        val spec = ChatActionCatalog.spec(soloCustomKey)
                        IconButton(onClick = { dispatchChatAction(soloCustomKey) }) {
                            Icon(
                                spec?.icon ?: Icons.Default.MoreVert,
                                contentDescription = spec?.let { stringResource(it.titleRes) } ?: "More",
                            )
                        }
                    } else if (soloCacheToggle) {
                        // Enhanced Cache as a lone top-bar toggle — same
                        // first-enable confirmation flow as the menu entry.
                        IconButton(
                            onClick = {
                                if (enhancedCacheOn) {
                                    viewModel.setEnhancedCacheEnabled(false)
                                } else if (viewModel.isEnhancedCacheConfirmed()) {
                                    viewModel.setEnhancedCacheEnabled(true)
                                } else {
                                    showEnhancedCacheDialog = true
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = stringResource(R.string.chat_menu_enhanced_cache),
                                tint = if (enhancedCacheOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    } else if (soloFastToggle) {
                        IconButton(onClick = { viewModel.setFastModeEnabled(!fastModeOn) }) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = stringResource(R.string.chat_menu_fast_mode),
                                tint = if (fastModeOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    } else if (hasAnyMenuItems) {
                        Box {
                            IconButton(onClick = { showChatMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            MinisMenu(
                                expanded = showChatMenu,
                                onDismissRequest = { showChatMenu = false },
                            ) {
                            // [T-android-memory-enabled-minisconfig] Gate the
                            // "Memories in Session" item below on the session's
                            // live memoryEnabled — when memory is off the entry
                            // disappears, consistent with the per-session gating
                            // of the memory_get / memory_write tools and the
                            // system-prompt injection. (menuMemoryEnabled is
                            // collected at the top of ChatScreen, next to
                            // chatActions, so the menu and the drawer footer
                            // stay in sync.)
                            // [T-customizable-chat-menu] The eight action /
                            // session entries below are driven by
                            // ChatMenuPrefs (Settings → Appearance → Chat
                            // Menu): the user can hide entries and reorder
                            // them. Rendering follows chatActions.menuOrder
                            // (missing keys appended in default order, unknown
                            // keys dropped) and skips entries whose visibility
                            // switch is OFF. Conditional entries (Skills /
                            // MCPs / Memory) additionally keep their runtime
                            // gate: an entry only shows when BOTH the user
                            // toggle AND the runtime condition are true, so
                            // hiding it never resurrects a dead repository.
                            // Model-invocation toggles (Enhanced Cache, Fast
                            // Mode) and the DEBUG crash trigger are NOT part
                            // of this list — they stay pinned at the bottom.
                            // Taps funnel through dispatchChatAction() (hoisted
                            // near the top of ChatScreen) so the "..." menu and
                            // the history-drawer footer share one implementation
                            // per action. EXPORT opens ExportFormatSheet instead
                            // of the old inline JSON/Plain submenu.
                            for (entryKey in chatActions.menuOrder) {
                                if (!chatActions.isVisible(entryKey)) continue
                                key(entryKey) {
                                    when (entryKey) {
                                        ChatMenuPrefs.TERMINAL -> {
                                            // Open Terminal (iOS parity) — session-bound, starts in /var/minis
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_open_terminal)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.TERMINAL)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Terminal, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.BROWSER -> {
                                            // Open Browser (iOS parity)
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_open_browser)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.BROWSER)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Language, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.CHAT_FILES -> {
                                            // Browse Chat Files (iOS parity) — opens file browser at /var/minis
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.CHAT_FILES)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Description, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.SLASH_COMMANDS -> {
                                            // Slash commands. Moved here from the dedicated "/"
                                            // circle button that used to sit next to "+" in the
                                            // composer: the button occupied permanent space in
                                            // the input row for an action most users invoke by
                                            // simply typing "/", and the row is the most
                                            // contended horizontal space on the screen. The
                                            // menu keeps it discoverable for people who don't
                                            // know the typed shortcut. Placed directly above
                                            // Token Usage per the requested ordering.
                                            //
                                            // Behaviour is unchanged from the old button: it
                                            // toggles, so opening the menu while the slash
                                            // sheet is already up dismisses it. (Toggle logic
                                            // lives in dispatchChatAction so the footer entry
                                            // behaves identically.)
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.chat_menu_slash_commands)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    dispatchChatAction(ChatMenuPrefs.SLASH_COMMANDS)
                                                },
                                                leadingIcon = {
                                                    // Match the old button's italic-bold "/"
                                                    // glyph rather than substituting a generic
                                                    // icon, so the entry reads as the same
                                                    // affordance that moved.
                                                    Text(
                                                        "/",
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontStyle = FontStyle.Italic,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.EXPORT -> {
                                            // Export conversation (JSON / Plain Text) — the
                                            // session list's long-press Export, surfaced from
                                            // inside the chat. Same streaming ChatExporter, so
                                            // long chats stay bounded-memory. Placed between
                                            // Slash Commands and Token Usage per the requested
                                            // ordering.
                                            //
                                            // [bottom-toolbar-customizable] The old inline
                                            // JSON/Plain submenu is gone: both the "..." menu
                                            // and the history-drawer footer now open the same
                                            // shared ExportFormatSheet, so the format choice
                                            // lives in exactly one implementation.
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.sessionlist_export)) },
                                                onClick = {
                                                    showChatMenu = false
                                                    showExportFormatSheet = true
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Share, contentDescription = null)
                                                },
                                            )
                                        }
                                        ChatMenuPrefs.SESSION_SKILLS -> {
                                            // Session Skills (iOS parity)
                                            if (skillRepository != null) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.session_skills_title)) },
                                                    onClick = {
                                                        showChatMenu = false
                                                        dispatchChatAction(ChatMenuPrefs.SESSION_SKILLS)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Build, contentDescription = null)
                                                    },
                                                )
                                            }
                                        }
                                        ChatMenuPrefs.SESSION_MCPS -> {
                                            // [T-mcp-integration-android] MCPs in Session, next to Skills.
                                            if (mcpRepository != null) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.session_mcps_title)) },
                                                    onClick = {
                                                        showChatMenu = false
                                                        dispatchChatAction(ChatMenuPrefs.SESSION_MCPS)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Extension, contentDescription = null)
                                                    },
                                                )
                                            }
                                        }
                                        ChatMenuPrefs.SESSION_MEMORY -> {
                                            // Session Memory (iOS parity)
                                            if (memoryRepository != null && menuMemoryEnabled) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.session_memory_title)) },
                                                    onClick = {
                                                        showChatMenu = false
                                                        dispatchChatAction(ChatMenuPrefs.SESSION_MEMORY)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Psychology, contentDescription = null)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // Enhanced Cache (iOS parity, commit 57aaf122):
                            // 1-hour Anthropic cache TTL. Only shown for the
                            // official Anthropic API (not relays / other
                            // providers) — showEnhancedCacheToggle recomputes on
                            // model/provider switch. First enable prompts a
                            // one-time extra-billing confirmation. (These
                            // flags are collected above, next to the "..."
                            // visibility check.)
                            if (showEnhancedCache) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_enhanced_cache)) },
                                    onClick = {
                                        if (enhancedCacheOn) {
                                            viewModel.setEnhancedCacheEnabled(false)
                                        } else if (viewModel.isEnhancedCacheConfirmed()) {
                                            viewModel.setEnhancedCacheEnabled(true)
                                        } else {
                                            showChatMenu = false
                                            showEnhancedCacheDialog = true
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bolt, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = enhancedCacheOn,
                                            onCheckedChange = {
                                                if (enhancedCacheOn) {
                                                    viewModel.setEnhancedCacheEnabled(false)
                                                } else if (viewModel.isEnhancedCacheConfirmed()) {
                                                    viewModel.setEnhancedCacheEnabled(true)
                                                } else {
                                                    showChatMenu = false
                                                    showEnhancedCacheDialog = true
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                            // [T-codex-fast-mode] Fast Mode (iOS parity,
                            // fb671083 + 838ba929): shown when the active model
                            // is a gpt-family model served through the
                            // Responses path (useResponsesAPI instance or Codex
                            // OAuth). App-level persisted toggle; while on, the
                            // Responses body carries service_tier="priority"
                            // (≈1.5x faster at 2x credit burn on the ChatGPT
                            // subscription) and the nav model row shows a ⚡
                            // badge. Sits next to Enhanced Cache — both are
                            // model-invocation controls (iOS 09944220 grouping).
                            if (showFastMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_fast_mode)) },
                                    onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bolt, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = fastModeOn,
                                            onCheckedChange = { viewModel.setFastModeEnabled(it) },
                                        )
                                    },
                                )
                            }
                            // T287: debug-only crash trigger so the user can verify
                            // ACRA/native crash log generation (T283). Throws a
                            // RuntimeException from the click handler — the
                            // uncaught-exception handler catches it and writes
                            // a crash-<stamp>.log under filesDir/logs/.
                            if (BuildConfig.DEBUG) {
                                MinisMenuDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.debug_trigger_crash_menu),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showChatMenu = false
                                        throw RuntimeException(
                                            "Debug crash triggered by user (T287)",
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.BugReport,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    } // [T-chat-menu-solo] end else-if chain: solo buttons / "..." overflow
                },
                windowInsets = WindowInsets.statusBars,
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatColors.background.copy(alpha = 0.92f),
                    scrolledContainerColor = ChatColors.background.copy(alpha = 0.92f),
                ),
                // [T-android-topbar-shrink] 76dp → 68dp. The earlier
                // T-topbar-model-row-clip fix bumped 60dp → 76dp to give the
                // 3-row title (14sp/lh17 + 12sp/lh14 + 11sp/lh13 ≈ 44sp text
                // + 4dp+2dp+1dp vertical padding ≈ 51dp on mdpi, mid-60s on
                // xxhdpi) room to breathe — but overshot, leaving visible
                // dead-space below the model row. This trim pairs with the
                // outer Column's vertical-padding drop (4dp→2dp above):
                // budget is now ~44sp text + 2dp+2dp+1dp ≈ 49dp typical,
                // ~58-62dp at xxhdpi 2.625× rounding. 68dp keeps a 6-10dp
                // safety margin so the model name still fits at any
                // user-configured font scale on xhdpi/xxhdpi without
                // re-clipping (T-topbar-model-row-clip regression check).
                // Font sizes + lineHeights stay untouched per spec.
                expandedHeight = 68.dp,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Dismiss keyboard when the USER scrolls the messages. Gated on
            // `isUserDragging` (a real finger drag) rather than
            // `listState.isScrollInProgress` — the latter is also true during
            // the streaming auto-follow's programmatic glide, so the old code
            // hid the keyboard + cleared focus on every streaming tick, which
            // closed the IME mid-stream and dropped the user's in-flight
            // keystroke. [T-android-composer-input-blocked-while-streaming]
            LaunchedEffect(isUserDragging) {
                if (isUserDragging) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }

            // Messages + scroll-to-bottom button
            Box(modifier = Modifier.weight(1f)) {
                var toolBarHeightPx by remember { mutableStateOf(0) }
                val density = LocalDensity.current
                val toolBarHeightDp = with(density) { toolBarHeightPx.toDp() }
                // T166 / T170 / T173: bottomReserve must clear the visible
                // top of the floating tool-status overlay. Layout primitives
                // come from FloatingToolStatusBar:
                //   - status bar height = 38 dp
                //   - thumbnail floats over the bar with overhang = 27 dp
                //   - thumbnail TOP = bar top - overhang = 65 dp above the
                //     input bar's upper edge (which is also the LazyColumn
                //     bottom edge under reverseLayout).
                //
                // `onGloballyPositioned` on the wrapper Box reports ~98 dp
                // because it includes wrapper padding(bottom=6) + horizontal
                // padding insets + shadow allowance — none of which are
                // *visually occluding* the LazyColumn. Using the measured
                // value + 8 dp left a ~25 dp gap above the thumbnail (red
                // box in the user's report).
                //
                // Pin to the visual constant: thumbnail height (65 dp) + a
                // visual buffer (18 dp) so the latest row's bottom has clear
                // breathing room above the thumbnail top.
                //
                // T174: an earlier version gated reserve on `toolBarHeightPx
                // > 0`, but `onGloballyPositioned` fires asynchronously after
                // the first floating-bar layout pass; for one frame after
                // toolBlocks appeared the reserve evaluated the small
                // default (28 dp) and the just-arrived user bubble landed
                // beneath the bar. Logcat showed the inverse glitch too:
                // `toolBarHeightPx=258 toolBarHeightDp=0 reserve=28` — the
                // px state and the dp/reserve values come from different
                // recomposition snapshots. Drive the reserve directly off
                // the same predicate used for *whether* the floating bar is
                // emitted (`hasFloatingTools` below) so reserve and bar
                // visibility flip on the same frame.
                // [T-android-chat-cannot-scroll-bottom-many-tools]
                // Bug 𝙓𝙄𝙉 TG36286: with 7+ tools the user couldn't scroll the
                // last messages above the floating tool status bar.
                //
                // Asymmetry between the bar's render condition and its
                // bottomReserve gate: [lastToolBlocks] (drives whether to
                // mount FloatingToolStatusBar) merges `messages` with the
                // streaming-side-channel `streamingById`, so during a live
                // turn the in-flight tool's toolStatus shows up there →
                // bar renders. [hasFloatingTools] (drives bottomReserve)
                // only read `messages`, which the streaming architecture
                // intentionally leaves stable during a turn — so the
                // in-flight tool is invisible to this predicate → reserve
                // collapsed to 20dp while a 65dp+6dp floating bar covered
                // the bottom of the LazyColumn. The new arrivals (status
                // pill, "Minis is thinking" indicator, inline retry banner) landed
                // behind the bar with no way to scroll them into view.
                //
                // Fix: also subscribe to streamingById so the predicate
                // matches the bar's actual mount condition. The bar's
                // mount uses `lastToolBlocks.isNotEmpty()` over the merged
                // view; we mirror that semantically by checking the same
                // filter on both sources.
                val streamingById by viewModel.streamingById.collectAsState()
                val hasFloatingTools = remember(messages, streamingById) {
                    val merged = if (streamingById.isEmpty()) messages
                                 else mergeStreamingOverlay(messages, streamingById)
                    merged.any { msg ->
                        msg.role == "assistant" && msg.toolBlocks.any { tb ->
                            tb.toolStatus != null && tb.kind != "thinking" && tb.kind != "info"
                        }
                    }
                }
                val visualOverlayHeight = 65.dp  // thumbnailHeight in FloatingToolStatusBar
                // Halve the breathing room above the input bar in both
                // states — felt too sparse before. The thumbnail's 65dp
                // physical height is preserved (it has to clear the
                // floating overlay).
                //
                // T245: buffer raised 9dp → 14dp so the gap between the
                // last LazyColumn tool row and the floating thumbnail's
                // top reads at least as loose as the inter-tool spacing
                // (each ToolCallPill carries padding(vertical = 3.dp) +
                // LazyColumn spacedBy(2.dp) = ~8dp inter-tool gap; the
                // 9dp buffer combined with the floating bar's internal
                // overhang was visually tighter than 8dp). 14dp also
                // matches the no-tool branch — single visual constant
                // for "row-bottom → bottom chrome" breathing room.
                // [T-bottom-occluded 0a6d3c92] No-tools branch bumped from
                // 14dp → 20dp to give the last message bubble a comfortable
                // gap above the composer's top edge. With 14dp the trailing
                // line sat too close to the composer shadow / rounded edge
                // (user reported "the bottom of the text is slightly clipped"). The floating-tools branch
                // already reserves visualOverlayHeight (65dp) + buffer and
                // was not part of the report; keep its +14 buffer.
                val bottomReserve =
                    if (hasFloatingTools) visualOverlayHeight + 14.dp else 20.dp
                // T174: when bottomReserve changes (toolbar appearing /
                // disappearing or thumbnail height shift), re-pin to bottom
                // if we are currently following. Without this, the new
                // contentPadding is honoured for layout but reverseLayout
                // won't actively scroll the list — items can wind up below
                // the viewport's new bottom edge until the next streaming
                // delta triggers a follow. iOS does the same in V3:54-68
                // when contentInset.bottom changes.
                LaunchedEffect(bottomReserve) {
                    // [T-android-send-no-autoscroll-behind-preview] Send-grace
                    // bypass: within SEND_FOLLOW_GRACE_MS of a user message
                    // append the isNearBottom gate is waived — the user JUST
                    // sent (userScrolledAway was explicitly reset), but the
                    // freshly-inserted user/thinking rows leave the live
                    // anchor transiently "not at bottom", which used to skip
                    // this pin and strand the new rows behind the floating
                    // tool bar when the reserve grew. All gates stay in force
                    // outside the grace window, so the C2 stream-end
                    // protections are untouched (a stream end is never
                    // within 2s of the user's send).
                    val sinceSendMs = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendMs in 0..SEND_FOLLOW_GRACE_MS
                    if (!userScrolledAway && (isNearBottom.value || sendGrace)) {
                        val reason = if (!isNearBottom.value) "send-grace" else "near-bottom"
                        tracedScrollToItem("reserve-change/$reason", 0, 0)
                    }
                }
                // T120: removed three streaming-time LaunchedEffects that
                // each called scrollToItem(0) on every chunk:
                //   - LE(bottomReserve): toolbar resize re-snap
                //   - LE(lastToolCount): per-tool-pill follow
                //   - LE(lastAwaiting):  thinking-indicator follow
                // They fired several times per second during streaming and
                // turned every layout pass into a scroll command, fighting
                // reverseLayout's native bottom-anchor behavior. With
                // reverseLayout=true Compose already keeps the visual
                // bottom pinned when the list is at offset 0; if the user
                // scrolled away, the JumpToBottom FAB is the explicit
                // affordance to return.

                // Flatten each message into multiple LazyColumn items so that older blocks
                // (text / tool pills / thinking) are frozen LazyList items while only the
                // last streaming block changes height. This keeps scroll-hovering stable:
                // LazyListState anchors on a stable item key + pixel offset, and inserting
                // or growing the trailing item never disturbs earlier items.
                //
                // T94: long sessions (hundreds of messages, deep tool chains) made the
                // flatten step expensive enough to stall composition on the main thread —
                // every streaming token recomposed the parent and re-ran the O(N · blocks)
                // walk inside `remember`, producing visible jank and ANRs on slower
                // devices. Run the flatten on Dispatchers.Default and publish the result
                // through a snapshot-state field so the LazyColumn renders the previous
                // frame's list while the next one computes. Keyed on `sessionId` so a
                // chat-switch resets the cache; LaunchedEffect(messages) reruns the
                // computation on every new emission.
                var flatItems by remember(sessionId) {
                    mutableStateOf<List<FlatChatItem>>(emptyList())
                }
                // [T-android-coldload-offmain-parse] Composition-snapshot
                // prewarmer (captures the markdown palette) used by the
                // flatten effect below to warm the parse caches for the
                // viewport-candidate fragments off-main.
                val prewarmMarkdown = rememberMarkdownPrewarmer()
                // [T-android-jank-diag-logging] Cold-open one-line summary
                // state: emitted ONCE per session open at the first
                // firstItem.placed; prewarmMs is filled by the parallel
                // prewarm when (if) it has finished by then, else -1.
                var lastColdPrewarmMs by remember(sessionId) { mutableStateOf(-1L) }
                var coldOpenSummaryEmitted by remember(sessionId) { mutableStateOf(false) }
                val screenMountAtMs = remember(sessionId) { System.currentTimeMillis() }
                // T-streaming-side-channel: messages-level changes (new
                // message, retry, etc.) AND streamingById deltas both feed
                // buildFlatChatItems, but we subscribe to streamingById
                // INSIDE LaunchedEffect (not at top-level) so per-token
                // emissions don't recompose the surrounding ChatScreen
                // scope. The flatten still runs per token (cheap-ish; ran
                // before too), but the rebuild stays off the main UI
                // composable's invalidation list.
                LaunchedEffect(messages, sessionId) {
                    // [T-android-stream-pipeline-incremental] Frozen/live split.
                    //
                    // `messages` is CONSTANT within this effect (the effect is
                    // keyed on it and the streaming turn writes high-frequency
                    // fields into the streamingById side-channel, never the
                    // canonical list). So the rows for every message BEFORE the
                    // first streamed one (= the frozen prefix) can be computed
                    // ONCE per effect lifetime and reused by reference on every
                    // tick. Per tick we only rebuild the live suffix (usually a
                    // single message). Pre-split, every 80ms tick re-flattened
                    // ALL messages (1146 rows on the ANR-loop session), re-ran
                    // splitMarkdownIntoBlockTexts over every frozen message,
                    // and allocated the whole row set fresh — the 130–180MB/s
                    // GC storm and the 100s builds in minis-2026-06-10.log.
                    //
                    // Row-for-row equivalence with the old full build holds by
                    // construction: buildFlatChatItems' neighbor lookbacks
                    // (precededByUser / isResumeContinuation) only ever read
                    // EARLIER messages, the live suffix is built against the
                    // full merged list with fromIndex (lookbacks cross the
                    // boundary), and dedupe continuity is preserved via
                    // seedKeys. Frozen rows are the same instances every tick,
                    // so LazyColumn's key+equals skip path sees ZERO change.
                    //
                    // Throttle (unchanged): conflate() + sample(80) keeps UI
                    // publication at ~12fps regardless of token rate.
                    var frozenRows: List<FlatChatItem> = emptyList()
                    var frozenKeys: Set<String> = emptySet()
                    var frozenSplitIdx = -1
                    var streamWasActive = false
                    // [T-android-stream-pipeline-incremental] Flush the perf
                    // turn when this effect is CANCELLED mid-turn: the
                    // turn-end drain emits `_messages` FIRST (restarting this
                    // messages-keyed effect) and clears the side-channel
                    // after, so the cancelled collector never sees the
                    // empty-stream tick that would fire turnEnd — without the
                    // finally, same-session turns accumulate forever and no
                    // [StreamPerf] summary is ever emitted.
                    try {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream -> msgs to stream }
                        .conflate()
                        .sample(80L)
                        .collect { (msgs, stream) ->
                            val tickStartNs = System.nanoTime()
                            if (stream.isNotEmpty() && !streamWasActive) {
                                streamWasActive = true
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnStart(sessionId)
                            }
                            // First message carrying a live overlay; everything
                            // before it is frozen. Empty stream → whole list is
                            // frozen (covers cold open and post-drain ticks).
                            val splitIdx = if (stream.isEmpty()) {
                                msgs.size
                            } else {
                                val i = msgs.indexOfFirst { stream.containsKey(it.id) }
                                if (i < 0) msgs.size else i
                            }
                            val frozenReused = splitIdx == frozenSplitIdx
                            if (!frozenReused) {
                                val tBuildStart = System.nanoTime()
                                val wasEmptyPre = flatItems.isEmpty()
                                // [T-android-perf-logging] Mark the start of a
                                // full (first / non-streaming) build so the
                                // gap to buildFlatChatItems.firstBuild bounds
                                // the construction cost in isolation.
                                if (wasEmptyPre && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "buildFlatChatItems.start",
                                        "msgCount=${msgs.size}",
                                    )
                                }
                                val rows = withContext(Dispatchers.Default) {
                                    // [T-android-flatitems-sublist-cme] Pass a
                                    // SNAPSHOT COPY, not msgs.subList(...). A
                                    // subList is a live VIEW backed by msgs and
                                    // shares its modCount; building off-main
                                    // (Dispatchers.Default) while msgs is
                                    // concurrently replaced — and the nested
                                    // messages.subList(idx+1, …).all{} inside
                                    // buildFlatChatItems iterating that view —
                                    // threw ConcurrentModificationException from
                                    // a later frame's SubList.equals. Copying
                                    // severs the view so it can't comodify.
                                    buildFlatChatItems(msgs.take(splitIdx), sessionId)
                                }
                                val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                                frozenRows = rows
                                frozenKeys = rows.mapTo(HashSet()) { it.key }
                                frozenSplitIdx = splitIdx
                                // [T-android-coldload-offmain-parse] Parallel
                                // viewport prewarm: block-parse + inline-warm
                                // the newest (viewport-candidate) markdown
                                // fragments off-main so the first frame's rows
                                // compose as cache HITs. Deliberately launched
                                // in PARALLEL with the flatItems publish, not
                                // before it — blocking the publish would add
                                // the parse latency to time-to-first-frame,
                                // the exact thing this task removes; rows the
                                // prewarm hasn't reached yet just take the
                                // placeholder-then-swap path in
                                // MarkdownBlockBody. Cold/full builds only
                                // (stream empty) — live ticks never get here.
                                if (stream.isEmpty() && rows.isNotEmpty()) {
                                    val prewarmRowLimit = 16
                                    val prewarmCharBudget = 96_000
                                    val raws = mutableListOf<String>()
                                    var charSum = 0
                                    for (item in rows.asReversed()) {
                                        if (raws.size >= prewarmRowLimit || charSum >= prewarmCharBudget) break
                                        val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
                                        raws.add(raw)
                                        charSum += raw.length
                                    }
                                    if (raws.isNotEmpty()) {
                                        launch(Dispatchers.Default) {
                                            val tPrewarmNs = System.nanoTime()
                                            prewarmMarkdown(raws)
                                            val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                            lastColdPrewarmMs = prewarmMs
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "coldPrewarm.done",
                                                "rows=${raws.size} chars=$charSum prewarmMs=$prewarmMs",
                                            )
                                        }
                                    }
                                }
                                // Only emit on the first non-streaming build per
                                // session (cheap reentry-path marker) or whenever
                                // build takes >50 ms (i.e. real work).
                                if ((wasEmptyPre || buildMs >= 50) && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        if (wasEmptyPre) "buildFlatChatItems.firstBuild"
                                        else "buildFlatChatItems.slow",
                                        "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                                    )
                                    // [T-android-perf-logging] Low-memory risk
                                    // flag: a very high row count is the single
                                    // biggest contributor to cold-open GC
                                    // pressure.
                                    if (rows.size > 3000) {
                                        com.openminis.app.diagnostics.PerfLongCtx.step(
                                            sessionId,
                                            "buildFlatChatItems.highRowCount",
                                            "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                                        )
                                    }
                                }
                            }
                            // Live suffix: only the streamed message(s). Built
                            // against the merged FULL list so neighbor lookbacks
                            // across the frozen/live boundary stay correct.
                            // sessionId = null keeps the hot path log-free.
                            val liveRows = if (splitIdx >= msgs.size) {
                                emptyList()
                            } else {
                                withContext(Dispatchers.Default) {
                                    val merged = mergeStreamingOverlay(msgs, stream)
                                    buildFlatChatItems(merged, null, fromIndex = splitIdx, seedKeys = frozenKeys)
                                }
                            }
                            flatItems = if (liveRows.isEmpty()) frozenRows else frozenRows + liveRows
                            com.openminis.app.diagnostics.StreamPerfMonitor.tick(
                                flattenNanos = System.nanoTime() - tickStartNs,
                                frozenReused = frozenReused,
                                frozenRows = frozenRows.size,
                                liveRows = liveRows.size,
                            )
                            if (stream.isEmpty() && streamWasActive) {
                                streamWasActive = false
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                            }
                        }
                    } finally {
                        // Effect cancelled (turn-end drain emit / session
                        // switch / screen dispose) — flush the open turn.
                        if (streamWasActive) {
                            com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                        }
                    }
                }
                // T304: when a new tool-use item appears at the trailing
                // edge (head of flatItems with reverseLayout=true), pin
                // back to the bottom so the just-arrived tool card is
                // visible above the floating Computer overlay + composer.
                //
                // The streaming-content snapshotFlow (LE around L798) does
                // detect `m.toolBlocks.size` growth, but it fires on the
                // raw `messages` model — and the LazyColumn renders the
                // async-flattened `flatItems`. The scroll can run BEFORE
                // flatItems repopulates with the new tool item, so item 0
                // is still the previous trailing item; the new tool block
                // ends up appended below the visible viewport. Pinning
                // again keyed on `flatItems` head fixes the race without
                // disturbing T281/T282 (those still own user-send and
                // resume scroll). userScrolledAway is honoured so users
                // reading history aren't yanked back.
                // [T-android-send-no-autoscroll-behind-preview] Key of the
                // trailing tool/typing row we last pinned for — dedupes the
                // pin to once per new row across flatten publishes.
                var lastTrailingPinKey by remember(sessionId) { mutableStateOf<String?>(null) }
                LaunchedEffect(flatItems) {
                    // Pin once per new trailing tool/typing row, key-deduped,
                    // not on every flatten publish, so streaming tool-arg
                    // ticks don't fight the user. flatItems is oldest-first
                    // (rendered via asReversed()), so the newest row is at
                    // the end.
                    val newest = flatItems.lastOrNull() ?: return@LaunchedEffect
                    if (newest !is FlatChatItem.AssistantToolUse &&
                        newest !is FlatChatItem.AssistantTyping
                    ) return@LaunchedEffect
                    if (newest.key == lastTrailingPinKey) return@LaunchedEffect
                    if (userScrolledAway) return@LaunchedEffect
                    // [P0-0-jump-fix] Gate on isNearBottom too: content
                    // insertion (new typing/tool row at index 0) pushes the
                    // visible viewport from index 0 to index 1 WITHOUT the
                    // user dragging, so userScrolledAway stays false. Without
                    // this check the pin fires and yanks the user to the new
                    // row even though they are no longer at the bottom.
                    if (!isNearBottom.value) return@LaunchedEffect
                    if (listState.isScrollInProgress) return@LaunchedEffect
                    val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                    if (sinceInterrupt < 1000L) return@LaunchedEffect
                    // Pin fires when EITHER (a) we're inside the send-grace
                    // window (the original "freshly sent, snap the typing
                    // row up" case), OR (b) the agent loop is actively
                    // streaming AND the user hasn't manually scrolled away.
                    // History readers fail (b) on userScrolledAway.
                    val sinceSendForPin = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendForPin <= SEND_FOLLOW_GRACE_MS
                    val streamingActive = viewModel.isStreaming.value && !userScrolledAway
                    if (!sendGrace && !streamingActive) return@LaunchedEffect
                    lastTrailingPinKey = newest.key
                    tracedScrollToItem("trailing-row/${newest.contentType}", 0, 0)
                }
                // messageId → isCompactedHistory map. Used to fade entire
                // assistant-row clusters (header + text + tool pills) at
                // render time — mirrors iOS isCompactedHistory opacity(0.5).
                // The lookup uses the underlying message id stripped of any
                // dedupe suffix (`id#2`) added by buildFlatChatItems.
                val grayedMap = remember(messages) {
                    messages.associate { it.id to it.isCompactedHistory }
                }
                fun originalMessageId(id: String): String =
                    id.substringBefore('#')

                // ─── [P0-0] focus-a-message: scroll + transient highlight ───
                //
                // Shared primitive for "open this session AND land on this
                // message" (search results, bookmarks, translation, range
                // export). Three constraints came out of the source audit and
                // each one is load-bearing:
                //
                //   1. flatItems is published ASYNCHRONOUSLY (frozenRows +
                //      liveRows). Scrolling on first composition would search
                //      an empty list, get -1 and silently do nothing — the
                //      exact race the trailing-row pin above documents. So the
                //      effect keys on flatItems and waits for a non-empty list.
                //   2. The LazyColumn is reverseLayout=true and renders
                //      `flatItems.asReversed()`, so the index handed to
                //      scrollToItem must be computed in REVERSED space.
                //      Using the oldest-first index would jump to the mirror
                //      position at the other end of the conversation.
                //   3. buildFlatChatItems disambiguates duplicate ids with a
                //      `#n` suffix, so matching goes through
                //      originalMessageId() on both sides.
                //
                // The target is consumed once (cleared after the jump) so a
                // config change or back-from-terminal recomposition does not
                // re-yank a user who has since scrolled elsewhere.
                LaunchedEffect(pendingFocusId, flatItems) {
                    val target = pendingFocusId ?: return@LaunchedEffect
                    if (flatItems.isEmpty()) return@LaunchedEffect
                    val rendered = flatItems.asReversed()
                    val idx = rendered.indexOfFirst {
                        originalMessageId(it.owningMessageId()) == originalMessageId(target)
                    }
                    if (idx < 0) {
                        // Message not in this session (deleted, or a stale
                        // link). Consume the request so we don't re-scan on
                        // every subsequent flatten publish, and leave the list
                        // at its default anchor rather than guessing.
                        AppLogger.debug(
                            "ChatFocus",
                            "focus target not found session=$sessionId target=$target rows=${flatItems.size}",
                        )
                        pendingFocusId = null
                        return@LaunchedEffect
                    }
                    tracedScrollToItem("FOCUS-MESSAGE", idx, 0)
                    highlightedMessageId = originalMessageId(target)
                    // Clearing pendingFocusId cancels THIS coroutine (it is a
                    // key of the enclosing LaunchedEffect), so the highlight
                    // fade-out must not live here — it gets its own effect
                    // below keyed on highlightedMessageId.
                    pendingFocusId = null
                }
                // [P0-0] Auto-expire the highlight. Separate from the jump
                // effect on purpose: see the note above about the jump's
                // coroutine being cancelled the moment it consumes the target.
                LaunchedEffect(highlightedMessageId) {
                    if (highlightedMessageId == null) return@LaunchedEffect
                    // Long enough for the eye to catch the row after the jump
                    // settles, short enough that it doesn't read as permanent
                    // selection state.
                    kotlinx.coroutines.delay(1_600L)
                    highlightedMessageId = null
                }
                fun FlatChatItem.isCompacted(): Boolean = when (this) {
                    is FlatChatItem.UserBubble -> grayedMap[originalMessageId(message.id)] == true
                    is FlatChatItem.AssistantHeader -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantText -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantMarkdownBlock -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantThinking -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantToolUse -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantInfo -> false  // system rows never grayed
                    is FlatChatItem.AssistantTyping -> false
                    is FlatChatItem.AssistantError -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantLegacyContent -> grayedMap[originalMessageId(messageId)] == true
                }
                // SelectionContainer must wrap the WHOLE LazyColumn — placing
                // it per-item breaks long-press because items get disposed
                // when scrolled out and the selection registrar/detector goes
                // with them. One outer SelectionContainer registers each Text
                // child as it enters composition, and the long-press gesture
                // detector lives at this stable scope. Mirrors Compose's
                // recommended LazyColumn + selection pattern.
                //
                // The custom LocalTextToolbar replaces the system Copy bar
                // with a 3-button popup (Copy / Copy Markdown / Copy Rich
                // Text); the latter two read from the bounds registry which
                // each AssistantMessageView updates via onGloballyPositioned.
                val messageBounds = remember { MessageBoundsRegistry() }
                // [T-selection-add-to-input] Toolbar's "Add to Chat Input"
                // action funnels the selected substring back into the
                // composer via the same StateFlow that the TextField is
                // bound to. Capture `viewModel` by reference so the
                // toolbar instance survives recomposition without
                // re-creation.
                // [T-add-to-input-focus] After append, request focus on the
                // composer + pop the soft keyboard so the user can keep
                // typing without an extra tap. Keyboard `show()` is best-effort
                // (controller may be null pre-attach); focus is guarded against
                // FocusRequester-not-attached the same way the auto-focus path
                // elsewhere in this file is.
                // MinisTextKit selection controller — declared BEFORE the
                // markdown toolbar so the toolbar can read table actions off it
                // ([T-android-markdown-table-copy-actions]). Hoisted ABOVE the
                // LazyColumn so item dispose can't kill the selection: when a
                // shard scrolls out of viewport it deregisters its TextShard,
                // but the (messageId, shardId, charOffset) endpoints stay valid;
                // scrolling back in re-registers the shard and the highlight
                // redraws automatically.
                val selectionController = remember { SelectionController() }
                // [T-android-selection-readaloud] Player backing the selection
                // toolbar's "Read Aloud". Screen-scoped and independent of the
                // voice panel's own player (that one only exists while voice
                // mode is active), so reading a selection works any time. Built
                // lazily on first use — an unused ChatScreen never binds a TTS
                // engine — and shut down with the screen.
                val selectionReader = remember { LazyReadAloudPlayer(context) }
                DisposableEffect(selectionReader) {
                    onDispose { selectionReader.shutdown() }
                }
                val markdownToolbar = remember(context, messageBounds, viewModel, inputFocusRequester, keyboardController, selectionController, selectionReader) {
                    MinisMarkdownTextToolbar(
                        context = context,
                        registry = messageBounds,
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try {
                                inputFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                                // FocusRequester not yet attached — composer
                                // will gain focus on next user tap.
                            }
                            keyboardController?.show()
                        },
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        selectionController = selectionController,
                    )
                }
                // Wrap any callback that truncates / replaces / removes rows from
                // the message list. Hiding the toolbar + clearing focus tears down
                // the SelectionManager's pending toolbar update before the
                // SelectionContainer subtree gets reshuffled — without this,
                // notifySelectionUpdateEnd → updateSelectionToolbar → getContentRect
                // → sort hits stale LayoutCoordinates and crashes with
                // "layouts are not part of the same hierarchy".
                val safeMutate: (() -> Unit) -> Unit = { block ->
                    markdownToolbar.hide()
                    focusManager.clearFocus()
                    block()
                }
                // Hoist slash-menu state up so the LazyColumn pointerInput
                // tap-spy below can react to it. The popup itself, declared
                // further down near the composer, reads viewModel.showSlashMenu
                // again — both subscriptions snap to the same StateFlow.
                val slashMenuOpen by viewModel.showSlashMenu.collectAsState()
                // T4: mirror state hoist for the mention picker so the chat-list
                // tap-spy can dismiss it the same way as the slash popup.
                val mentionMenuOpenForSpy by viewModel.showMentionMenu.collectAsState()
                // Intercept back press to dismiss slash/mention menus before
                // navigating away from the chat screen.
                androidx.activity.compose.BackHandler(
                    enabled = slashMenuOpen || mentionMenuOpenForSpy
                ) {
                    if (slashMenuOpen) {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    }
                    if (mentionMenuOpenForSpy) {
                        viewModel.dismissMentionMenu()
                    }
                }
                // (selectionController declared above, before markdownToolbar.)
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMessageBoundsRegistry provides messageBounds,
                    androidx.compose.ui.platform.LocalTextToolbar provides markdownToolbar,
                    LocalMinisSelectionController provides selectionController,
                ) {
                // Hoisted out of AlwaysStretchOverscrollBox lambda so
                // SelectionDragTracker (which lives outside the lambda) can
                // read the LazyColumn's window-space root coords for edge
                // auto-scroll calculations.
                var listRootCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                // fires once per session when the LazyColumn first reports
                // a layout. Combined with `buildFlatChatItems.firstBuild`
                // (above) and `lazyColumn.firstItem.placed` (below) this
                // tells us whether the bottleneck is row-list build,
                // initial list measure, or per-row composition.
                val perfFirstLayoutFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                Box {
                AlwaysStretchOverscrollBox { sharedEffect ->
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    // T30: when no tool status bar is rendered, a small bottom
                    // padding keeps the latest message off the composer's
                    // top edge so the conversation breathes. Reuses the same
                    // bottomReserve when the toolbar is present.
                    // Tuned so the visible gap to the composer's outer edge is ~18dp.
                    //
                    // [T-android-chat-first-message-top-padding] top reduced
                    // 12dp → 4dp. Under reverseLayout this top padding sits at
                    // the VISUAL top, so the first message's gap below the model
                    // title bar was top(12) + the first bubble's own top(4) =
                    // 16dp (≈44px @ 440dpi) — looser than needed. 4dp here +
                    // the bubble's 4dp = 8dp (≈22px), tighter but still a clear
                    // breath under the title bar. Bottom padding and inter-
                    // message spacing are untouched.
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = if (bottomReserve == 0.dp) 12.dp else bottomReserve,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned {
                            listRootCoords = it
                            if (perfFirstLayoutFired.compareAndSet(false, true)) {
                                val info = listState.layoutInfo
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstLayout",
                                    "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                                )
                            }
                        }
                        .minisTextKitSelectionGesture(
                            controller = selectionController,
                            listState = listState,
                            rootCoordinates = { listRootCoords },
                            // Chat LazyColumn is reverseLayout=true; auto-
                            // scroll sign needs to flip so dragging toward
                            // the bottom edge reveals NEWER messages (lower
                            // index) rather than jumping backward.
                            reverseLayout = true,
                        )
                        // T29 dismiss-on-tap spy. Only active while the slash
                        // popup is showing. awaitFirstDown(requireUnconsumed=false,
                        // pass=Initial) lets us see the tap *before* any child
                        // gesture (LazyColumn scroll, message long-press) without
                        // consuming it — the gesture continues to its real
                        // handler. We close the menu on the very first finger
                        // down anywhere inside the chat list, exactly like
                        // tapping outside an iOS popover.
                        .pointerInput(slashMenuOpen, mentionMenuOpenForSpy) {
                            if (!slashMenuOpen && !mentionMenuOpenForSpy) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                )
                                if (slashMenuOpen) {
                                    viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                                }
                                if (mentionMenuOpenForSpy) {
                                    viewModel.dismissMentionMenu()
                                }
                            }
                        },
                    // T303: anchor items to the visual bottom so a newly
                    // streamed tool card / typing indicator that arrives
                    // before older items have shifted up still lands inside
                    // the visible area. Without this, reverseLayout's
                    // default arrangement (anchored to viewportStart) leaves
                    // a gap at the bottom of the list when the bottom item
                    // is just-inserted with offset=0 — the new card renders
                    // behind the composer and `gap = vpEnd - itemBottom`
                    // hits ~1300 px while listState still reports
                    // firstVisible=0, firstOffset=0 (logged as the "tool on
                    // screen but not pushed into view" repro on Pixel 4a).
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
                    overscrollEffect = sharedEffect,
                ) {
                    // T13 Resume banner — placed BEFORE items() so reverseLayout
                    // renders it at the visual bottom of the list (just below
                    // the last assistant message). Mirrors iOS resumeBanner in
                    // CollectionViewMessageListV3.swift:360. Hidden while
                    // streaming or when an error banner is showing.
                    //
                    // T114: also hide when the last assistant message carries
                    // a message-level error — the inline Retry banner already
                    // covers that turn, and showing both at once is confusing
                    // (Resume on a turn that hit rate-limit would just retrace
                    // into the same failure).
                    val lastAssistantHasError = messages
                        .lastOrNull { it.role == "assistant" }
                        ?.error
                        ?.isNotBlank() == true
                    if (canResume && !isStreaming && error == null && !lastAssistantHasError) {
                        item(key = "__resume_banner__", contentType = "resume_banner") {
                            ResumeBanner(onResume = {
                                viewModel.resume()
                                // T282: same dual-scroll trick as the regular
                                // send paths (T281). Resume kicks off a fresh
                                // stream, so the "Minis is thinking" indicator
                                // mounts a frame or two later — pin once now,
                                // then again after 100ms so the indicator
                                // doesn't land below the fold.
                                userScrolledAway = false
                                coroutineScope.launch {
                                    tracedScrollToItem("RESUME-BANNER/initial", 0, 0)
                                    kotlinx.coroutines.delay(100)
                                    tracedScrollToItem("RESUME-BANNER/settle", 0, 0)
                                }
                            })
                        }
                    }
                    items(
                        items = flatItems.asReversed(),
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                        // the newest message (index 0 in reverseLayout) is
                        // the first row painted in the viewport — its
                        // onPlaced is the moment the user actually sees
                        // content. SideEffect fires on first composition
                        // (before measure); onPlaced fires after layout.
                        if (item == flatItems.lastOrNull()) {
                            androidx.compose.runtime.SideEffect {
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstItem.compose",
                                )
                            }
                        }
                        // [Perf][LongCtx] aggregate compose-count tracker.
                        // Each row that enters composition during the reentry
                        // burst increments the per-session counter. When the
                        // 10th and 50th rows hit, emit one line each carrying
                        // the wall-time since `lazyColumn.firstLayout` plus
                        // the row's class — gives a "per-N-rows compose
                        // budget" signal without per-row log spam.
                        com.openminis.app.diagnostics.PerfLongCtx.maybeReportRowComposed(
                            sessionId,
                            item::class.java.simpleName,
                        )
                        // 0.4f matches iOS .opacity(0.5) closely once Compose's
                        // sRGB compositing is factored in. Renders below normal
                        // intensity but the message stays selectable + readable.
                        val rowAlpha = if (item.isCompacted()) 0.4f else 1f
                        // [T-HANG-DIAG] log on first composition of any item
                        // whose content is large enough to be a likely hang
                        // suspect. SideEffect runs after the first successful
                        // composition; if rendering stalls on the way to that
                        // SideEffect, we'll see the LAUNCH-RENDER line for it
                        // immediately followed by the watchdog's HANG dump
                        // and the missing FINISH-RENDER tells us this is the
                        // item that locked up the layout pass. Gated on size
                        // so normal turns don't spam the log.
                        val tHangDiagLen = remember(item.key) {
                            when (item) {
                                is FlatChatItem.UserBubble -> item.message.content.length
                                is FlatChatItem.AssistantText -> item.messageMarkdown.length
                                else -> 0
                            }
                        }
                        if (tHangDiagLen >= 50_000) {
                            androidx.compose.runtime.SideEffect {
                                println(
                                    "[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} " +
                                        "type=${item::class.java.simpleName} len=$tHangDiagLen",
                                )
                            }
                            androidx.compose.runtime.DisposableEffect(item.key) {
                                onDispose {
                                    println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)")
                                }
                            }
                        }
                        val isNewestItem = item == flatItems.lastOrNull()
                        // [P0-0] Transient focus highlight. Matching on the
                        // owning message id (not item.key) means every row of
                        // a multi-row message — header, text blocks, tool
                        // pills — lights up as one unit, which is what reads
                        // as "this message" to the user.
                        //
                        // Drawn as a background tint on the existing row Box
                        // rather than a border/scale so it cannot shift layout
                        // (a size change here would perturb the very scroll
                        // anchor we just positioned) and cannot interfere with
                        // text selection.
                        val isFocusHighlighted = highlightedMessageId != null &&
                            originalMessageId(item.owningMessageId()) == highlightedMessageId
                        val focusTint by animateColorAsState(
                            targetValue = if (isFocusHighlighted) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                            animationSpec = tween(durationMillis = if (isFocusHighlighted) 180 else 520),
                            label = "focusHighlight",
                        )
                        Box(
                            modifier = Modifier
                                .alpha(rowAlpha)
                                .background(focusTint)
                                .then(
                                    if (isNewestItem) {
                                        Modifier.onPlaced {
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "lazyColumn.firstItem.placed",
                                                "size=${it.size.width}x${it.size.height}",
                                            )
                                            // [T-android-jank-diag-logging]
                                            // One quotable line per session
                                            // open, after the first frame's
                                            // newest row has laid out.
                                            if (!coldOpenSummaryEmitted) {
                                                coldOpenSummaryEmitted = true
                                                val totalChars = messages.sumOf { m -> m.content.length }
                                                val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                                AppLogger.info(
                                                    "JankDiag",
                                                    "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                                        "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
                                                        "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
                                                        "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                                )
                                                // [T-android-content-perf-diag] Per-large-message structural
                                                // fingerprint so a future hang report maps straight to "which
                                                // message, what structure" without re-querying the DB. Gated at
                                                // 5000 chars — small messages never drive a render hang.
                                                messages.forEachIndexed { idx, m ->
                                                    if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                                        val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                                        AppLogger.info(
                                                            "Perf",
                                                            "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                                "streaming=${m.isStreaming} ${s.asLogFields()}",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                        when (item) {
                            is FlatChatItem.UserBubble -> {
                                // User bubbles intentionally don't register
                                // MinisTextKit shards — long-press on a user
                                // bubble shows its own action menu (Copy /
                                // Retry / Edit) instead of starting text
                                // selection, matching iOS UX.
                                UserMessageBubble(
                                message = item.message,
                                // [T-android-candidate-bubble-gap] extra top
                                // gap when this bubble directly follows another
                                // user bubble (back-to-back candidate sends).
                                precededByUser = item.precededByUser,
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
                                },
                                // T119: pass null while a turn is in flight so
                                // the long-press menu hides Retry; once the
                                // stream stops (cancel or natural end) the
                                // option reappears. Gating execution alone
                                // wasn't enough — users still saw a tappable
                                // Retry that silently no-op'd.
                                onRetry = if (isStreaming) null else ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RETRY-FROM-MSG", 0, 0)
                                    }
                                    safeMutate { viewModel.retryFromMessage(item.message.id) }
                                }),
                                // T187: long-press → Edit pulls the user message
                                // text into the composer; the next send truncates
                                // from this turn (inclusive) before persisting
                                // the edited content. Gated on isStreaming the
                                // same way Retry is.
                                onEdit = if (isStreaming || item.message.isQueued) null else ({
                                    val prefill = viewModel.editMessage(item.message.id)
                                    if (prefill != null) {
                                        viewModel.setInputText(prefill)
                                        coroutineScope.launch {
                                            tracedScrollToItem("EDIT-MSG", 0, 0)
                                        }
                                        inputFocusRequester.requestFocus()
                                    }
                                }),
                                onWithdraw = if (item.message.isQueued) {
                                    { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                                } else null,
                                onPreviewFile = { uri, name ->
                                    // T150: turn the persisted file:// URI back
                                    // into a FileItem and hand off to the host
                                    // navigator (FilePreviewScreen). Mirrors
                                    // FileBrowser's onPreviewFile contract so
                                    // both entry points share one screen.
                                    val file = uri.path?.let { java.io.File(it) }
                                    if (file != null && file.exists()) {
                                        onPreviewAttachment(
                                            com.openminis.app.ui.sandbox.FileItem(
                                                file = file,
                                                name = name,
                                                isDirectory = false,
                                                isSymlink = false,
                                                size = file.length(),
                                                modifiedMs = file.lastModified(),
                                            )
                                        )
                                    }
                                },
                            )
                            } // close UserBubble SideEffect + UserMessageBubble block
                            is FlatChatItem.AssistantHeader -> AssistantHeader()
                            is FlatChatItem.AssistantText -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "text:${item.block.id}",
                                markdown = item.messageMarkdown,
                            ) {
                                // T-android-gc-storm-issue17: collapse oversized frozen
                                // assistant text before feeding the markdown parser, which
                                // is the GC-storm hotspot for legacy sessions.
                                LargeContentGuard(
                                    content = item.block.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "text:${item.messageId}:${item.block.id}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.block.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "text:${item.block.id}",
                                        ),
                                    )
                                }
                            }
                            is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.rawText,
                                    isStreaming = item.isStreaming,
                                    stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    MarkdownBlock(
                                        rawText = item.rawText,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                        ),
                                    )
                                }
                            }
                            is FlatChatItem.AssistantThinking -> {
                                // T300: hide Deep Thinking block when the user
                                // currently has thinking turned off — even if
                                // a forced-reasoning model (e.g. xAI Grok 4.x
                                // via OpenRouter) still streams reasoning_-
                                // content. Snapshot on the message wins so
                                // toggling the level after a turn finishes
                                // doesn't retro-hide an already-visible block;
                                // legacy DB-restored messages (snapshot=null)
                                // follow the chat's current level.
                                val effectiveLevel = item.messageThinkingLevel
                                    ?: viewModel.thinkingLevel.value
                                if (effectiveLevel.isEnabled) {
                                    // [T-android-thinking-auto-collapse] Use
                                    // `isLastBlockOverall` (not `isLast` =
                                    // last-thinking-only) so the block flips
                                    // to !isStreaming the moment a sibling
                                    // text/tool_use arrives — that's the
                                    // edge ThinkingBlock's LaunchedEffect
                                    // hooks for auto-collapse, matching iOS
                                    // ThinkingBlockView semantics.
                                    ThinkingBlock(
                                        block = item.block,
                                        isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                                        isLast = item.isLast,
                                    )
                                }
                            }
                            is FlatChatItem.AssistantToolUse -> ToolCallPill(
                                block = item.block,
                                allToolBlocks = item.allToolBlocks,
                                onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                                // T14: route per-card stop to the global
                                // cancelStream(). The button only renders
                                // when the block is RUNNING/STREAMING — see
                                // ToolCallPill `isRunning && onStop != null`
                                // — so passing it unconditionally is safe.
                                onStop = { viewModel.cancelStream() },
                                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                // T261: route detail open through ViewModel so
                                // the sheet is hoisted out of LazyColumn item
                                // scope (otherwise the sheet snaps shut when
                                // the pill scrolls off-screen and Compose
                                // disposes the item).
                                onOpenDetail = { viewModel.openToolDetail(it) },
                                // [T-android-rerun-from-tool-block-position]
                                // Re-run cuts at THIS tool_use block: keep the
                                // blocks before it in the same turn, drop it +
                                // everything after, then regenerate. The block
                                // id (== tool_use id for a tool_use block) is
                                // the stable anchor. Gated off while streaming
                                // (mutating an in-flight turn corrupts agent
                                // state, same rule as Retry on the user bubble).
                                // safeMutate tears down the selection toolbar
                                // before the truncation reshuffles the list.
                                onRerunFromHere = if (!isStreaming) ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RERUN-FROM-TOOL", 0, 0)
                                    }
                                    safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
                                }) else null,
                                onCopyDetails = {
                                    val text = formatToolDetailsForClipboard(item.block)
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.tool_longpress_copied_toast),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
                                block = item.block,
                                // Only the compact-divider info block should
                                // surface a "Revert Compact" button on its
                                // detail sheet — other info rows (slash
                                // notices, fallback notices) have nothing
                                // to revert.
                                onRevert = if (item.block.toolName == "compact") {
                                    { viewModel.revertCompact() }
                                } else null,
                            )
                            is FlatChatItem.AssistantTyping -> TypingIndicator()
                            is FlatChatItem.AssistantError -> InlineErrorBanner(
                                error = item.error,
                                onRetry = {
                                    coroutineScope.launch { tracedScrollToItem("INLINE-RETRY-LAST", 0, 0) }
                                    safeMutate { viewModel.retryLast() }
                                },
                            )
                            is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "legacy",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "legacy:${item.messageId}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "legacy",
                                        ),
                                    )
                                }
                            }
                        }
                        } // Box (alpha wrapper)
                    }
                    // [T-android-larky-longsession-followup] "Load older
                    // messages" header — placed AFTER items() so under
                    // reverseLayout it sits at the VISUAL TOP of the list.
                    // Only emitted when the session has trimmed older messages
                    // behind the window; tapping bumps the cap by
                    // VISIBLE_MESSAGE_CAP_STEP and the FlatChat pipeline
                    // rebuilds with the wider slice. The item key is stable so
                    // LazyListState's anchor (firstVisibleItem) survives the
                    // re-emission and the user keeps their scroll position
                    // relative to the message they were reading.
                    if (hasOlderMessages) {
                        item(key = "__load_older_messages__", contentType = "load_older") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewModel.loadOlderMessages() }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_load_older_messages),
                                    color = ChatColors.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                } // AlwaysStretchOverscrollBox
                // SelectionDragTracker bridges gesture-published dragIntent
                // with listState scroll observation — that's what keeps the
                // selection extending across newly-scrolled-in shards when
                // the user's finger is stationary in the edge auto-scroll
                // zone (the inline pointer loop can't see those because
                // it only fires on pointer events).
                SelectionDragTracker(
                    controller = selectionController,
                    listState = listState,
                    listRootCoordinates = { listRootCoords },
                    reverseLayout = true,
                )
                MinisMarkdownTextToolbarHost(markdownToolbar)
                // MinisTextKit floating toolbar — driven by selectionController.
                MinisSelectionToolbarHost(
                    controller = selectionController,
                    // Clamp the menu's vertical position inside the
                    // LazyColumn's viewport in window coords, so it can't
                    // float above the chat header or below the composer /
                    // navigation bar. Computed lazily so the menu picks up
                    // re-layout (rotation, IME show/hide, etc.) without us
                    // having to recompose this composable.
                    contentViewportBounds = {
                        val coords = listRootCoords
                        if (coords != null && coords.isAttached) {
                            val origin = coords.positionInWindow()
                            androidx.compose.ui.geometry.Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coords.size.width,
                                bottom = origin.y + coords.size.height,
                            )
                        } else null
                    },
                    actions = SelectionToolbarActions(
                        // Resolve the parent message's joined markdown via
                        // the bounds registry — only when the selection sits
                        // within a single message (cross-message selections
                        // return null and the markdown / rich-text buttons
                        // are hidden).
                        resolveSelectionMarkdown = {
                            // Use the controller's own cached
                            // message-markdown — survives both endpoint
                            // shards scrolling off-screen, unlike the rect-
                            // based MessageBoundsRegistry lookup whose
                            // entries are removed on shard dispose.
                            selectionController.selectionMessageMarkdown()
                        },
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                            keyboardController?.show()
                        },
                        // [T-android-selection-readaloud] Speak the selection
                        // through the same screen-scoped lazy player the
                        // Compose-SelectionContainer toolbar uses.
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                    ),
                )
                // iOS-style selection handle dots, one at each endpoint.
                MinisSelectionHandlesHost(
                    controller = selectionController,
                    listState = listState,
                    reverseLayout = true,
                )
                } // Box (selection scope)
                } // CompositionLocalProvider

                // Floating tool status bar — shows only actual tool calls (not text/thinking/info).
                // Matches iOS: filter on toolStatus != nil (text blocks have toolStatus = null).
                //
                // T-streaming-side-channel-tool-blocks: derive lastToolBlocks
                // from a state that combines messages + streamingById INSIDE
                // a LaunchedEffect (not via a top-level collectAsState read),
                // so streaming-tick churn stays off the ChatScreen invalidation
                // list. Without including streamingById, a tool pill clicked
                // mid-turn is missing from lastToolBlocks → ToolDetailSheet
                // never opens (and its sentinel LaunchedEffect immediately
                // closes the detail state because the id "doesn't exist").
                var lastToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
                LaunchedEffect(messages) {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream ->
                        val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
                        merged.filter { it.role == "assistant" }
                            .flatMap { it.toolBlocks }
                            .filter { it.toolStatus != null && it.kind != "thinking" && it.kind != "info" }
                    }.collect { lastToolBlocks = it }
                }
                val allToolBlocks = lastToolBlocks
                if (lastToolBlocks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onGloballyPositioned { toolBarHeightPx = it.size.height }
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        FloatingToolStatusBar(
                            toolBlocks = lastToolBlocks,
                            // T14: per-card stop on the floating bar — same
                            // global cancel as the message-list pill button.
                            onStop = { viewModel.cancelStream() },
                            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                            // T261: route detail open through the same VM
                            // state as in-list pills so both surfaces share
                            // one always-mounted sheet instance.
                            onOpenDetail = { viewModel.openToolDetail(it) },
                        )
                    }
                } else {
                    SideEffect { toolBarHeightPx = 0 }
                }

                // T261: tool-detail sheet hoisted out of LazyColumn item
                // scope. Visibility driven by ViewModel state so streaming /
                // pill-disposal / new-tool emissions can't snap it shut.
                // existence guard auto-closes the sheet when the underlying
                // block disappears (T258 retry-preserve removes in-flight
                // tools, clearChat, etc.). Reuses lastToolBlocks (already
                // computed above) so we don't traverse messages twice.
                val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsState()
                LaunchedEffect(selectedToolDetailId, lastToolBlocks) {
                    val id = selectedToolDetailId ?: return@LaunchedEffect
                    if (lastToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
                }
                val selectedToolBlock = selectedToolDetailId?.let { id ->
                    lastToolBlocks.firstOrNull { it.id == id }
                }
                if (selectedToolBlock != null) {
                    val initialIdx = lastToolBlocks
                        .indexOfFirst { it.id == selectedToolBlock.id }
                        .coerceAtLeast(0)
                    ToolDetailSheet(
                        toolBlocks = lastToolBlocks,
                        initialIndex = initialIdx,
                        onDismiss = { viewModel.closeToolDetail() },
                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                        onOpenBrowserForUrl = { url ->
                            viewModel.closeToolDetail()
                            viewModel.openBrowserSheetForUrl(url)
                        },
                    )
                }

                // Scroll-to-bottom FAB (iOS: circle chevron.down, bottom-right)
                // T138 phase 2 v3: show on user-scroll intent, not transient
                // layout state. Otherwise the FAB flickers whenever multi-tool
                // emissions briefly bump the bottom item off-screen during
                // re-anchoring.
                //
                // T170: gate also on `contentOverflows` so short sessions
                // (one Q+A on a tall screen) never flash the FAB if an IME
                // animation produces a synthetic drag-stop. iOS gets this
                // for free via `maxOffset > 0`; Compose needs the explicit
                // check.
                // [T-android-scroll-to-first-message] Floating "scroll to first
                // message" up-button. Mirrors iOS: shown ONLY in the middle
                // region (isFarFromTop && isFarFromBottom — more than one screen
                // from both ends) so it never lingers at the bottom or on short
                // chats. Sits ABOVE the scroll-to-bottom button (same BottomEnd
                // anchor, extra bottom padding = down-button height 36dp + 10dp
                // spacing). Scrolls to the OLDEST message (highest index under
                // reverseLayout).
                if (messages.isNotEmpty() && isFarFromTop.value && isFarFromBottom.value) {
                    val upBaseBottom = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            coroutineScope.launch {
                                val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                tracedScrollToItem("FAB-UP", lastIndex, 0)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = upBaseBottom + 46.dp)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Scroll to first message",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (userScrolledAway && contentOverflows.value && messages.isNotEmpty()) {
                    val fabBottomPadding = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            // [T-android-scroll-fab-down-stuck] Clear the
                            // scrolled-away intent SYNCHRONOUSLY on tap — that
                            // alone hides the FAB (its gate is userScrolledAway).
                            // Don't rely on the at-bottom auto-reset LE
                            // (`isNearBottom && userScrolledAway → false`): on a
                            // long reverseLayout session scrollToItem(0,0) can
                            // settle on a non-zero firstVisibleItemIndex while
                            // unmeasured items resolve (logged: FAB-DOWN tap left
                            // firstIdx=41/60, canBwd=false), so isNearBottom stays
                            // false, the auto-reset never fires, and the FAB was
                            // stuck visible. The user tapped "go to bottom" — the
                            // intent is unambiguous, so reset directly.
                            userScrolledAway = false
                            coroutineScope.launch {
                                tracedScrollToItem("FAB-DOWN", 0, 0)
                                // Second pin after a frame: the first scroll may
                                // land short while late-measuring items shift the
                                // true bottom; re-issue once layout settles.
                                kotlinx.coroutines.delay(100)
                                tracedScrollToItem("FAB-DOWN/settle", 0, 0)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = fabBottomPadding)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // T51 / T185: the "Move to…" capsule was previously rendered
                // here, on top of the message list. After the user actually
                // sends the share-injected turn, the capsule was overlapping
                // the user-message bubble area and obscuring attachment chips.
                // Moved to the composer's top-right corner — see the Box
                // overlay around the input Column below, mirroring iOS
                // AIChatView.swift:1817 (.overlay(alignment: .topTrailing)).

                // T-chat-title-pill: sticky session title overlay. Sits
                // above the LazyColumn (top-center), animates in once the
            }

            // T-chat-title-pill-edit: reuse SessionEditSheet from the session
            // list (same composable, exposed `internal`) so title + category
            // edits from the in-chat pill are visually + behaviourally
            // identical to the home-screen long-press flow.
            editingSession?.let { session ->
                com.openminis.app.ui.sessions.SessionEditSheet(
                    session = session,
                    onDismiss = { editingSession = null },
                    onSave = { newTitle, newCategory ->
                        viewModel.updateTitleAndCategory(newTitle, newCategory)
                        editingSession = null
                    },
                )
            }

            // ─── Input area (iOS-style: rounded box with text + buttons below) ───
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 2.dp, bottom = 8.dp),
            ) {
                // T13 banner moved INSIDE the LazyColumn so it renders at the
                // visual end of the message list (mirrors iOS — see the
                // banner item before items() in the LazyColumn block above).

                // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
                // a Popup so it overlays content (tool status bar, chat list)
                // instead of pushing them up. Anchored above the composer via
                // PopupProperties so its bottom edge sits just above this Column.
                // Tap-outside dismisses via dismissOnClickOutside.
                val showSlashMenu by viewModel.showSlashMenu.collectAsState()
                val filteredSlashCommands = remember(
                    showSlashMenu,
                    viewModel.slashFilter.collectAsState().value,
                    viewModel.memoryEnabled.collectAsState().value,
                    viewModel.thinkingLevel.collectAsState().value,
                ) { viewModel.filteredSlashCommands() }

                if (showSlashMenu && filteredSlashCommands.isNotEmpty()) {
                    val thinkingLevelState by viewModel.thinkingLevel.collectAsState()
                    val thinkingSupported = viewModel.currentModelSupportsReasoning
                    val memoryOnState by viewModel.memoryEnabled.collectAsState()
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    // Anchor: top-edge of the composer column. Place
                                    // the popup so its bottom sits 12dp above that edge.
                                    // T301: bumped from 6dp — at 6dp the panel was
                                    // visually glued to the composer; 12dp gives a
                                    // clear breathing gap matching the iOS spacing.
                                    val gap = 12
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = {
                            viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                        },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // dismissOnClickOutside=false: with focusable=false the popup
                            // never receives focus, so the system "click outside" detector
                            // can't tell a tap on the BasicTextField below from a tap on
                            // the chat list — flipping this off would dismiss the popup
                            // every time the IME caret was moved. Dismiss is driven from
                            // the chat list / topbar tap-spy below instead, which lets
                            // the input field keep focus while still closing the menu
                            // when the user clearly looks elsewhere.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                        // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
                        // Short lists show empty space below the last row;
                        // long lists scroll inside the same frame with a
                        // visible scroll indicator. Prevents installed
                        // Skills + built-ins from pushing the menu past
                        // the input bar / off the top of the screen.
                        val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                // T240: keep a thin visible border instead of the
                                // diffuse 8dp halo that bled out past the panel edge.
                                .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                state = slashListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SLASH_PICKER_FIXED_HEIGHT)
                                    .verticalScrollbar(slashListState),
                            ) {
                            itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                                // Section divider between builtins and
                                // installed Skills (mirrors iOS divider
                                // at the first skill row). Drawn as the
                                // top of the skill row, not between every
                                // row — keeps the menu visually grouped
                                // without splitting every command.
                                if (cmd.isSkill && index > 0 && !filteredSlashCommands[index - 1].isSkill) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = ChatColors.toolBorder,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                val isThinking = cmd.id == "thinking"
                                val isThinkingActive = isThinking && thinkingLevelState.isEnabled && thinkingSupported
                                val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                val subtitleColor = if (isThinking && !thinkingSupported) {
                                    ChatColors.secondaryText
                                } else if (isThinkingActive) {
                                    ChatColors.sendButton.copy(alpha = 0.7f)
                                } else ChatColors.secondaryText
                                val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let {
                                            if (!isThinking) {
                                                it.clickable {
                                                    // [T-android-slash-menu-clears-input] Pass the
                                                    // LIVE input so an action command keeps the
                                                    // user's body text instead of wiping it.
                                                    viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                                    // For Skill rows, "/<name> "
                                                    // is a typing aid — the user
                                                    // still needs to type
                                                    // arguments. Bring the IME
                                                    // back up + grab focus so
                                                    // they can keep typing
                                                    // without an extra tap on
                                                    // the composer.
                                                    if (cmd.isSkill) {
                                                        try {
                                                            inputFocusRequester.requestFocus()
                                                        } catch (_: IllegalStateException) {
                                                            // FocusRequester not attached yet.
                                                        }
                                                        keyboardController?.show()
                                                    }
                                                }
                                            } else if (thinkingSupported) {
                                                it.clickable {
                                                    val newLevel = if (thinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                                    viewModel.setThinkingLevel(newLevel)
                                                }
                                            } else it
                                        }
                                        // [T-android-slash-menu-density] Tighter
                                        // vertical padding (10→7) so slash rows
                                        // read as compact as iOS, not sparse.
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = cmd.icon,
                                        contentDescription = null,
                                        tint = iconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "/${cmd.title.lowercase()}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = titleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        // Cap to one line + ellipsis (mirrors
                                        // iOS T-slash-picker-product-rules
                                        // 051896e2). Long Skill descriptions
                                        // would otherwise stretch the row,
                                        // breaking the locked 4-row band and
                                        // crowding the menu visually.
                                        Text(
                                            text = cmd.subtitle,
                                            fontSize = 11.sp,
                                            color = subtitleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (cmd.id == "memory") {
                                        Icon(
                                            imageVector = if (memoryOnState) Icons.Default.CheckCircle else Icons.Default.Block,
                                            contentDescription = null,
                                            tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (isThinking && thinkingSupported) {
                                        ThinkingLevelPicker(
                                            current = thinkingLevelState,
                                            // [T-android-thinking-level-arch] Only
                                            // offer tiers the bound model supports.
                                            availableLevels = viewModel.availableThinkingLevels,
                                            onSelect = { level -> viewModel.setThinkingLevel(level) },
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }
                }

                // T4: @ file-mention picker — same anchoring + tap-spy
                // contract as the slash popup (mutually exclusive in the VM,
                // so they never both render). Reuses Popup so the bar over
                // the composer is consistent and respects IME inset.
                val showMentionMenu by viewModel.showMentionMenu.collectAsState()
                val mentionEntries by viewModel.mentionEntries.collectAsState()
                val isMentionScanning by viewModel.isMentionScanning.collectAsState()
                val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsState()
                if (showMentionMenu) {
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    val gap = 6
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = { viewModel.dismissMentionMenu() },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // Same rationale as the slash popup: dismissOnClickOutside=false
                            // because the input field below the popup sits in the
                            // "outside" region (focusable=false → caret moves still
                            // count as outside). The chat-list tap-spy that drives
                            // dismissSlashMenu also dismisses this menu via
                            // dismissMentionMenu(); see the LazyColumn pointerInput.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            if (mentionEntries.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isMentionScanning) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 1.5.dp,
                                            color = ChatColors.secondaryText,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = stringResource(
                                            if (isMentionScanning) R.string.mention_scanning
                                            else R.string.mention_no_match
                                        ),
                                        fontSize = 13.sp,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            } else {
                                // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                                // Mention picker shares the slash picker's
                                // locked 192dp height (4 rows × 46dp + 8dp)
                                // so both popups have the same band on screen.
                                val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                // Keep the highlighted row visible when the user
                                // navigates with a hardware keyboard. iOS gets this
                                // for free from SwiftUI's List/scrollTo binding;
                                // mimic it explicitly here.
                                LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                                    val idx = mentionSelectedIndex
                                    if (idx in mentionEntries.indices) {
                                        mentionListState.animateScrollToItem(idx)
                                    }
                                }
                                LazyColumn(
                                    state = mentionListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(SLASH_PICKER_FIXED_HEIGHT)
                                        .verticalScrollbar(mentionListState),
                                ) {
                                    itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                        val isSelected = i == mentionSelectedIndex
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                )
                                                .clickable {
                                                    val (newText, newCaret) = viewModel.selectMention(
                                                        entry,
                                                        currentText = inputFieldValue.text,
                                                        currentCaret = inputFieldValue.selection.end,
                                                    )
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Single doc icon for every entry — the scope/mount
                                            // capsule on the right already labels what bucket
                                            // this is (workspace / skills / shared / memory /
                                            // <mountName>). iOS varies the icon per scope but
                                            // we keep it uniform here so the row stays
                                            // visually consistent at small sizes on Pixel 4a.
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                tint = ChatColors.secondaryText,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.basename,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.primaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = entry.displayPath,
                                                    fontSize = 11.sp,
                                                    color = ChatColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            // Scope / mount badge — matches iOS capsule.
                                            Text(
                                                text = entry.mountName ?: entry.scope.displayLabel,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = ChatColors.secondaryText,
                                                modifier = Modifier
                                                    .background(
                                                        ChatColors.toolCapsuleBg,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Input box: iOS-style floating card — no visible border, separated
                // from the backdrop by a symmetric soft shadow painted by hand
                // (Android's Modifier.shadow only casts downward).
                val inputBgArgb = ChatColors.inputBg.toArgb()
                val shadowPaint = remember(inputBgArgb) {
                    android.graphics.Paint().apply {
                        color = inputBgArgb
                        isAntiAlias = true
                    }
                }
                // T185: Move-to-session capsule mirrors iOS
                // AIChatView.swift:1816 (.overlay(alignment: .topTrailing))
                // on the input card. We render it as the first child of the
                // composer Column, right-aligned, so it visually sits inside
                // the input card's top-right corner — Compose doesn't have a
                // free overlay primitive that doesn't need a Box wrapper,
                // and an in-flow Row at the top with Arrangement.End is the
                // cleanest equivalent.
                val showMoveCapsule by viewModel.hasInjectedShareContent.collectAsState()
                // Mirrors iOS swipe-up-to-send: drag the input bar upward —
                // if it holds text, a floating send-arrow + "Release to send"
                // capsule track the finger; releasing past `swipeArmFraction`
                // sends. With empty text + collapsed keyboard, releasing
                // activates the keyboard instead. Box wraps the existing
                // composer Column so the gesture + overlay live in the same
                // coordinate space without disturbing the bar's own layout.
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var totalDy = 0f
                            var claimed = false
                            var lastPos = down.position
                            verticalDrag(down.id) { change ->
                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y
                                lastPos = change.position
                                if (!claimed) {
                                    // Wait until a clearly vertical drag of
                                    // at least `slop` px before claiming.
                                    // Below that the TextField / list still
                                    // get the events (taps, text scroll, …).
                                    if (kotlin.math.abs(totalDy) < slop) return@verticalDrag
                                    if (kotlin.math.abs(totalDy) <= kotlin.math.abs(totalDx)) return@verticalDrag
                                    claimed = true
                                }
                                change.consume()
                                if (totalDy < 0) {
                                    // Swiping up. Show hint only when there
                                    // is text to send; otherwise keep the
                                    // overlay hidden and defer keyboard
                                    // activation to onEnd.
                                    val hasText = viewModel.inputText.value.isNotBlank()
                                    if (hasText) {
                                        val newProgress = (-totalDy / swipeThresholdPx).coerceIn(0f, 1f)
                                        if (newProgress >= swipeArmFraction && sendSwipeProgress < swipeArmFraction) {
                                            swipeHaptics.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                            )
                                        }
                                        sendSwipeProgress = newProgress
                                        sendSwipeLocation = lastPos
                                    } else if (sendSwipeProgress != 0f) {
                                        sendSwipeProgress = 0f
                                    }
                                } else if (sendSwipeProgress != 0f) {
                                    // Reversed direction; clear any hint.
                                    sendSwipeProgress = 0f
                                }
                            }
                            // Drag ended (finger up or pointer cancel).
                            val hasText = viewModel.inputText.value.isNotBlank()
                            val swipedUp = claimed && totalDy < 0 &&
                                kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                            if (swipedUp && hasText) {
                                val attachmentsCount = viewModel.attachments.value.size
                                val canSendNow = hasText || attachmentsCount > 0
                                if (sendSwipeProgress >= swipeArmFraction && canSendNow) {
                                    // T-drag-send-queue: route through the
                                    // shared send-or-enqueue handler so a
                                    // drag-to-send during streaming enqueues
                                    // the prompt instead of being dropped —
                                    // matches the send-button tap path which
                                    // already enqueues mid-stream via
                                    // viewModel.sendMessage → enqueuePrompt.
                                    performSendOrEnqueue(viewModel.inputText.value)
                                }
                                sendSwipeProgress = 0f
                            } else if (swipedUp && !hasText && !inputFocused) {
                                // Empty input + collapsed keyboard -> bring
                                // up the keyboard. If the keyboard is
                                // already open, do nothing so a stray drag
                                // doesn't re-trigger anything.
                                inputFocusRequester.requestFocus()
                                keyboardController?.show()
                                sendSwipeProgress = 0f
                            } else {
                                sendSwipeProgress = 0f
                            }
                        }
                    },
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val radiusPx = 20.dp.toPx()
                            val canvas = drawContext.canvas.nativeCanvas
                            // Pass 1: symmetric ambient halo — small blur, low alpha.
                            shadowPaint.setShadowLayer(
                                6.dp.toPx(), 0f, 0f,
                                android.graphics.Color.argb(22, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                            // Pass 2: soft downward shadow (spot light).
                            shadowPaint.setShadowLayer(
                                10.dp.toPx(), 0f, 3.dp.toPx(),
                                android.graphics.Color.argb(24, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                        }
                        .padding(top = if (attachments.isNotEmpty()) 8.dp else 4.dp),
                ) {
                    // T185: Move-to capsule lives INSIDE the composer card,
                    // pinned 8dp from the top-right corner, mirroring iOS
                    // AIChatView.swift:1816 (.overlay(alignment: .topTrailing)
                    // padding(.top, 6).padding(.trailing, 10)). A Popup
                    // keeps it out of the composer's layout flow so the
                    // attachment row + text field still own the full
                    // vertical rhythm.
                    if (showMoveCapsule) {
                        // T185: align Move-to right edge with the
                        // attachment row + button row (both 12dp). The
                        // anchorBounds rect is in px, so convert via
                        // LocalDensity rather than treating the constant
                        // as dp directly.
                        val popupDensity = androidx.compose.ui.platform.LocalDensity.current
                        val rightInsetPx = with(popupDensity) { 12.dp.roundToPx() }
                        val topInsetPx = with(popupDensity) { 6.dp.roundToPx() }
                        androidx.compose.ui.window.Popup(
                            popupPositionProvider = remember(rightInsetPx, topInsetPx) {
                                object : androidx.compose.ui.window.PopupPositionProvider {
                                    override fun calculatePosition(
                                        anchorBounds: androidx.compose.ui.unit.IntRect,
                                        windowSize: androidx.compose.ui.unit.IntSize,
                                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                        popupContentSize: androidx.compose.ui.unit.IntSize,
                                    ): androidx.compose.ui.unit.IntOffset {
                                        val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                        val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                                        return androidx.compose.ui.unit.IntOffset(x, y)
                                    }
                                }
                            },
                            onDismissRequest = {},
                            properties = androidx.compose.ui.window.PopupProperties(
                                focusable = false,
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false,
                            ),
                        ) {
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                // Mirrors iOS .ultraThinMaterial — solid-
                                // looking pill against the input bg.
                                // Without a hairline border the capsule
                                // washed out into the input card on the
                                // light theme, which is why it stopped
                                // reading as a pill.
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    ChatColors.thumbnailBorder,
                                ),
                                modifier = Modifier.clickable { showMoveSheet = true },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                                ) {
                                    // arrow.right.circle look-alike: an
                                    // outlined ring around a → glyph.
                                    Box(
                                        modifier = Modifier
                                            .size(15.dp)
                                            .border(
                                                1.dp,
                                                ChatColors.secondaryText,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(10.dp),
                                            tint = ChatColors.secondaryText,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Move to…",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            }
                        }
                    }
                    // Attachment thumbnails inside the box (iOS: 64×64 squares)
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                // T185: 12dp horizontal so the row's left
                                // edge lines up with the +/slash button
                                // column and the typed text below.
                                .padding(horizontal = 12.dp),
                            // The chip itself now bakes in 8dp of trailing
                            // visual room for the remove badge that spills
                            // past the top-right; no extra spacedBy needed.
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(attachments, key = { it.id }) { attachment ->
                                // T-pwa-2: long-press menu only appears for
                                // .html / .htm attachments. The menu lives in
                                // a Box that anchors to the chip; the sheet
                                // itself is hosted at screen level (see
                                // webAppSheetTarget).
                                val isHtmlAttachment = attachment.fileName
                                    .substringAfterLast('.', "")
                                    .lowercase()
                                    .let { it == "html" || it == "htm" }
                                var webAppMenuExpanded by remember(attachment.id) { mutableStateOf(false) }
                                Box {
                                AttachmentChip(
                                    attachment = attachment,
                                    onRemove = { viewModel.removeAttachment(attachment.id) },
                                    // TODO(webapp-hidden): long-press opened
                                    // the WebApp "Add to Home Screen" menu —
                                    // disabled while entry point is hidden.
                                    // Re-enable by restoring `if (isHtmlAttachment)`.
                                    onLongClick = if (false && isHtmlAttachment) {
                                        { webAppMenuExpanded = true }
                                    } else null,
                                    onClick = {
                                        // Mirror iOS InputAttachmentTile
                                        // (AIChatView.swift:3699) which
                                        // .sheet's an AttachmentPreviewView
                                        // routed by file type. Images go
                                        // through the in-app fullscreen
                                        // viewer; non-image files take the
                                        // in-app FilePreviewScreen when we
                                        // hold a host file path, falling
                                        // back to the system viewer for
                                        // foreign content:// URIs.
                                        if (attachment.isImage) {
                                            // Collect every image chip in
                                            // the composer row so the user
                                            // can swipe through them.
                                            val imageChips = attachments.filter { it.isImage }
                                            val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                                .coerceAtLeast(0)
                                            previewImageGallery = imageChips.map { ic ->
                                                com.openminis.app.ui.components.ImageGalleryItem(
                                                    model = ic.uri,
                                                    caption = ic.fileName,
                                                )
                                            } to startIdx
                                        } else {
                                            // T162: shares funnel through
                                            // addAttachmentFromStagedShare,
                                            // which copies the bytes into
                                            // cacheDir/share_inbound/<uuid>-
                                            // <name> and returns a
                                            // Uri.fromFile() URI. Handing
                                            // that file:// URI directly to
                                            // Intent.ACTION_VIEW raises
                                            // FileUriExposedException on
                                            // API 24+ and crashed the app
                                            // on the user's first chip tap.
                                            // Route file:// chips into the
                                            // in-app FilePreviewScreen via
                                            // the host onPreviewAttachment
                                            // callback (same path the user-
                                            // bubble chip uses); leave
                                            // content:// chips on the
                                            // system viewer because we
                                            // don't have a host path for
                                            // those.
                                            val uri = attachment.uri
                                            val asFile = if (uri.scheme == "file") {
                                                uri.path?.let { java.io.File(it) }
                                            } else null
                                            if (asFile != null && asFile.exists()) {
                                                onPreviewAttachment(
                                                    com.openminis.app.ui.sandbox.FileItem(
                                                        file = asFile,
                                                        name = attachment.fileName,
                                                        isDirectory = false,
                                                        isSymlink = false,
                                                        size = asFile.length(),
                                                        modifiedMs = asFile.lastModified(),
                                                    )
                                                )
                                            } else {
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                ).apply {
                                                    setDataAndType(uri, attachment.mimeType)
                                                    addFlags(
                                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                                    )
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: android.content.ActivityNotFoundException) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "No app available to open this attachment.",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                )
                                // TODO(webapp-hidden): WebApp / "Add to Home
                                // Screen" entry point temporarily hidden —
                                // feature not yet validated/complete. Re-enable
                                // by removing `false &&` from the guard below.
                                if (false && isHtmlAttachment) {
                                    com.openminis.app.ui.components.MinisMenu(
                                        expanded = webAppMenuExpanded,
                                        onDismissRequest = { webAppMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.webapp_add_to_home)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.AppShortcut,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                webAppMenuExpanded = false
                                                webAppSheetTarget = attachment
                                            },
                                        )
                                    }
                                }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Composer input field. (Voice input mode was removed
                    // with the mic button; the field is now the only path.)
                    run {
                        val interactionSource = remember { MutableInteractionSource() }
                        val mergedTextStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.5.sp * chatInputFontScale,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // [T-android-enter-to-send-broken] Live read of the
                        // "Return key sends" preference. Bound here (not
                        // captured at BasicTextField construction) so a
                        // toggle in Settings reflects on the next IME
                        // commit without recomposing the chat tree.
                        val sendOnEnter = com.openminis.app.ui.settings
                            .returnKeySendsMessage(context)
                        // Shared "Enter pressed → send" body used by BOTH
                        // the hardware-keyboard onKeyEvent path AND the
                        // soft-keyboard KeyboardActions.onSend below.
                        // Pre-fix only the onKeyEvent path existed and
                        // most soft IMEs (Gboard, Sogou, MIUI) never
                        // route an Enter through onKeyEvent under
                        // ImeAction.Default — they just inserted a '\n'
                        // and the preference appeared not to work. We
                        // now flip imeAction to Send when the toggle is
                        // on, so the IME shows the send icon AND fires
                        // onSend; this lambda is the single source of
                        // truth for what "press Enter to send" means.
                        val performEnterSend: () -> Boolean = handler@{
                            if (inputText.isBlank() && attachments.isEmpty()) return@handler false
                            // Intercept slash commands so "/compact" et al.
                            // run locally instead of being sent as a chat
                            // turn. Mirrors iOS performSend().
                            if (viewModel.tryExecuteInputAsSlashCommand(inputText)) {
                                viewModel.setInputText("")
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                return@handler true
                            }
                            // T160: snapshot → clear state + IME →
                            // sendMessage. Same ordering as the send-
                            // button click; finishComposingText fires
                            // when focus drops so any IME composing
                            // buffer is committed/dropped before the
                            // empty inputText becomes visible.
                            val toSend = inputText
                            lastSendTimeMs = System.currentTimeMillis()
                            viewModel.setInputText("")
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            viewModel.sendMessage(toSend)
                            noteSendForInputModePref()
                            userScrolledAway = false
                            coroutineScope.launch {
                                tracedScrollToItem("SEND-PATH(keyboard-imeAction)/initial", 0, 0)
                                kotlinx.coroutines.delay(100)
                                tracedScrollToItem("SEND-PATH(keyboard-imeAction)/settle", 0, 0)
                            }
                            true
                        }
                        BasicTextField(
                            value = inputFieldValue,
                            onValueChange = { tfv ->
                                // T217-2: drop IME residue commits in 300ms post-send window.
                                // finishComposingText (fired by clearFocus on send) makes
                                // voice/Pinyin IMEs replay their pending candidate through
                                // onValueChange after we cleared inputText.
                                val now = System.currentTimeMillis()
                                if (now - lastSendTimeMs < 300L && tfv.text.isNotEmpty()) {
                                    return@BasicTextField
                                }
                                // [T-android-voice-correction] Capability #3:
                                // learn from select-and-replace edits. When the
                                // PREVIOUS value had a non-empty selection and
                                // this change swapped that span for different
                                // text, the user deliberately replaced something
                                // they had already written — the same shape as
                                // fixing a transcript, so the recorder applies
                                // the identical phonetic admission test and
                                // discards anything that reads as a rewrite.
                                //
                                // Silent background capture: consent-gated,
                                // fire-and-forget, no UI. Deliberately NOT
                                // firing on ordinary typing, which is
                                // append-only and carries no correction signal.
                                captureSelectionReplacement(context, inputFieldValue, tfv)
                                // [T-android-enter-to-send-multiline] Root cause:
                                // the composer is a multi-line BasicTextField
                                // (maxLines=6 ⇒ EditorInfo carries
                                // TYPE_TEXT_FLAG_MULTI_LINE, confirmed inputType
                                // 0x28001 in dumpsys input_method). In multi-line
                                // mode soft IMEs (Gboard/LatinIME, Sogou, MIUI)
                                // render Enter as a newline and IGNORE
                                // IME_ACTION_SEND — so KeyboardActions.onSend
                                // never fires and the "Return key sends" pref
                                // looked inert. The IME commits the Enter as a
                                // plain '\n' through onValueChange (not through
                                // onKeyEvent / a KEYCODE_ENTER), so the only
                                // place to catch it for soft keyboards is here.
                                //
                                // Detect a single '\n' freshly inserted into the
                                // text (one Enter keypress) and convert it to a
                                // send. Guarded to a single added newline so a
                                // paste containing newlines is NOT swallowed —
                                // those increase the count by >1 and fall through
                                // to the normal multi-line edit. Hardware-keyboard
                                // Enter / Shift+Enter still go through onKeyEvent
                                // below (Shift+Enter inserts a newline there and
                                // never reaches the send path).
                                if (sendOnEnter && !showMentionMenu) {
                                    val oldText = inputFieldValue.text
                                    val newText = tfv.text
                                    val addedNewline = newText.length == oldText.length + 1 &&
                                        newText.count { it == '\n' } == oldText.count { it == '\n' } + 1
                                    if (addedNewline) {
                                        val caret = tfv.selection.end
                                        // The inserted char sits just before the
                                        // caret; confirm it is the newline so we
                                        // don't misfire on an unrelated 1-char edit
                                        // that happens to keep newline parity.
                                        if (caret in 1..newText.length &&
                                            newText[caret - 1] == '\n'
                                        ) {
                                            performEnterSend()
                                            return@BasicTextField
                                        }
                                    }
                                }
                                inputFieldValue = tfv
                                if (inputText != tfv.text) {
                                    viewModel.setInputText(tfv.text)
                                    viewModel.updateSlashMenuState(tfv.text)
                                }
                                // Drive the @ mention picker on every keystroke
                                // and selection change — caret position alone
                                // can flip the active token's filter (e.g. user
                                // moves cursor without typing). VM filters out
                                // the slash-menu-priority case and any
                                // non-mention caret state.
                                viewModel.updateMentionMenuState(
                                    text = tfv.text,
                                    caret = tfv.selection.end,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 25.dp)
                                .focusRequester(inputFocusRequester)
                                .onFocusChanged { inputFocused = it.isFocused }
                                .onKeyEvent { event ->
                                    // T-at-filepicker-keyboard: while the @-mention
                                    // menu is open, hardware Up/Down navigates the
                                    // list and Return commits the highlighted entry.
                                    // Falls through to the normal Return-send path
                                    // when there are no mention candidates so the
                                    // user isn't stuck if the menu is empty.
                                    if (showMentionMenu && event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                viewModel.mentionMenuUp()
                                                return@onKeyEvent true
                                            }
                                            Key.DirectionDown -> {
                                                viewModel.mentionMenuDown()
                                                return@onKeyEvent true
                                            }
                                            Key.Enter -> {
                                                val result = viewModel.executeSelectedMention(
                                                    currentText = inputFieldValue.text,
                                                    currentCaret = inputFieldValue.selection.end,
                                                )
                                                if (result != null) {
                                                    val (newText, newCaret) = result
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                    return@onKeyEvent true
                                                }
                                                // Menu open but no candidates → fall
                                                // through to Return-send / newline.
                                            }
                                            Key.Escape -> {
                                                viewModel.dismissMentionMenu()
                                                return@onKeyEvent true
                                            }
                                            else -> Unit
                                        }
                                    }
                                    // Return-key behavior is user-configurable
                                    // (Appearance → Return Key, default Newline =
                                    // iOS shipping default). Shift+Enter always
                                    // inserts a newline regardless of the setting,
                                    // mirroring iOS hardware-keyboard semantics.
                                    // [T-android-enter-to-send-broken] Hardware-
                                    // keyboard path. Soft IME route goes through
                                    // KeyboardActions.onSend below.
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Enter &&
                                        !event.isShiftPressed &&
                                        sendOnEnter
                                    ) {
                                        performEnterSend()
                                    } else false
                                },
                            textStyle = mergedTextStyle,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            // [T-android-enter-to-send-broken] When the
                            // user has Return-Key=Send turned on, ask the
                            // IME for the Send action so it (a) shows the
                            // send glyph instead of "Enter" and (b)
                            // actually invokes KeyboardActions.onSend
                            // instead of silently inserting '\n'. With
                            // Default, Gboard / Sogou / MIUI etc. never
                            // routed Enter through onKeyEvent so the
                            // preference appeared inert.
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { performEnterSend() },
                            ),
                            interactionSource = interactionSource,
                            decorationBox = { innerTextField ->
                                OutlinedTextFieldDefaults.DecorationBox(
                                    value = inputText,
                                    innerTextField = innerTextField,
                                    enabled = true,
                                    singleLine = false,
                                    visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                    interactionSource = interactionSource,
                                    // Placeholder removed per user request: the
                                    // "Message <SoulName>" hint was dropped so
                                    // the composer starts visually empty. No
                                    // placeholder param = no hint text; the "@
                                    // to mention files" affordance is still
                                    // discoverable by typing "@".
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                    ),
                                    // T7: vertical 10dp → 7dp (≈ −15%) to slim the
                                    // chat composer. Settings TextFields keep
                                    // Material3 default padding — those are
                                    // 1-shot config inputs, not the daily-
                                    // friction surface the user wants tightened.
                                    // T185: 12dp horizontal lines the
                                    // typed text up with the +/slash and
                                    // mic/send icon-button row below
                                    // (Modifier.padding(horizontal = 12.dp)
                                    // there) and the attachment chip row
                                    // (also 12dp). 16dp left an unaligned
                                    // jog where the text started further
                                    // right than every other composer
                                    // element.
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 7.dp,
                                    ),
                                )
                            },
                        )
                    }

                    // Button row below text field (iOS layout: + / ... mic send)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // T185: 12dp horizontal lines the +/slash and
                            // mic/send icon-button column up with the
                            // attachment row + textfield + Move-to popup.
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left slot: model picker. Occupies the space the "+"
                        // and "/" buttons used to hold. Rationale: choosing a
                        // model is a per-turn decision made WHILE composing, so
                        // it belongs next to the text you are about to send,
                        // not in the nav bar where it competed with the session
                        // title for the same tap target. The nav bar keeps a
                        // read-only echo of the group name; this is the control.
                        //
                        // Same `showModelPicker` state as the nav-bar subtitle,
                        // so either entry point opens the identical sheet.
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ChatColors.inputBg,
                            modifier = Modifier.clickable { showModelPicker = true },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            ) {
                                // Green = a model resolved for this session,
                                // orange = binding unresolved. Mirrors the dot
                                // in the nav-bar subtitle so the two entry
                                // points can't disagree.
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (modelName.isNotEmpty()) Color(0xFF34C759) else Color(0xFFFF9500),
                                            CircleShape,
                                        ),
                                )
                                Text(
                                    // Show the CONCRETE model actually being
                                    // called, not the group name. `modelName`
                                    // is entry.model.displayName, resolved the
                                    // same way for both paths: picking a group
                                    // resolves through its routing/fallback
                                    // strategy to a concrete entry and sets
                                    // modelName to that; picking a single model
                                    // sets it directly. Either way this chip
                                    // names what the next turn will hit. The
                                    // group name still lives in the nav-bar
                                    // subtitle for people who want to see which
                                    // group is active. Fall back to provider,
                                    // then to the group name, only when no
                                    // model has resolved yet (brief window
                                    // during loadSession, or an unresolved
                                    // binding).
                                    text = modelName.ifEmpty {
                                        providerName.ifEmpty {
                                            selectedGroupName.ifEmpty {
                                                val defaultGroupId = providerRepository.defaultPrimaryGroupId
                                                availableGroups.firstOrNull { it.id == defaultGroupId }?.name
                                                    ?: stringResource(R.string.model_picker_default_badge)
                                            }
                                        }
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ChatColors.secondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // Cap the chip so a long model name can't
                                    // starve the mic/send cluster on a narrow
                                    // screen.
                                    modifier = Modifier.widthIn(max = 150.dp),
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = ChatColors.tertiaryText,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }

                        // The "/" slash-command circle button used to sit here.
                        // Moved into the top-right chat menu (above Token Usage)
                        // to reclaim composer width; typing "/" still opens the
                        // same sheet, so no functionality was removed.

                        // T187: Exit Edit Mode pill, only while editingMessageId
                        // is non-null. Tap clears the edit flag + composer text
                        // without truncating history. iOS parity:
                        // AIChatView.swift L1586 editExitButton.
                        val editingId by viewModel.editingMessageId.collectAsState()
                        if (editingId != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ChatColors.inputBg,
                                modifier = Modifier.clickable {
                                    viewModel.cancelEdit()
                                    viewModel.setInputText("")
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_edit_exit_button),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ChatColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Attach (+): moved from the left edge to sit
                        // directly beside mic/send. The three actions
                        // here (photos, file, camera) all produce
                        // something that gets SENT, so grouping them
                        // with the send affordance keeps the
                        // "compose -> attach -> send" gesture inside one
                        // thumb arc instead of spanning the full width.
                        Box {
                            InputCircleButton(
                                onClick = { showAttachMenu = true },
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Attach",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            MinisMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false },
                            ) {
                                // Order: Choose Photos & Videos / Add File / Take
                                // Photo. Diverges from the iOS ordering on
                                // purpose — picking existing media is by far the
                                // most frequent attach action, so it takes the
                                // first slot (closest to the thumb, and the
                                // default highlighted row), while Take Photo is
                                // the rarest and also the only destructive-ish
                                // one (it opens the camera and can lose the
                                // draft on some OEM camera apps), so it sits
                                // last where it can't be hit by accident.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_choose_photos_videos)) },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        mediaPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                            ),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_add_file)) },
                                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        // OpenMultipleDocuments takes a mime-
                                        // type array; "*/*" stays the wildcard.
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_take_photo)) },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.CAMERA,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            launchCamera()
                                        } else {
                                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                        }
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Voice input (mic button, language pill, inline
                        // voice panel, read-replies TTS) removed here.

                        // Right: 3-state Send / Enqueue / Stop button (mirrors iOS sendButton).
                        //   • streaming + hasText  → SEND (routes through viewModel.sendMessage,
                        //     which dispatches to enqueuePrompt since _isStreaming is true).
                        //     Visual feedback for the queued prompt comes from the dashed
                        //     bubble that ChatViewModel.enqueuePrompt appends to the message
                        //     list — no extra button badge needed (matches iOS).
                        //   • streaming + !hasText → STOP (cancel current run).
                        //   • !streaming           → SEND (full color when hasText, dimmed
                        //     when empty; same as before).
                        // T180: an attachments-only send (no caption) is a
                        // valid message — mirrors iOS where !attachments.isEmpty
                        // satisfies the composer's send guard. Without this an
                        // image-only "look at this" send is impossible.
                        val hasText = inputText.isNotBlank()
                        val hasContent = hasText || attachments.isNotEmpty()
                        val showStop = isStreaming && !hasContent
                        if (showStop) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(0xFFFF3B30), CircleShape)
                                    .clip(CircleShape)
                                    .clickable { viewModel.cancelStream() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            // Streaming with content → Send-into-queue; Idle with content → Send.
                            // Idle without text or attachments → disabled.
                            val canActivate = hasContent
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        if (canActivate) ChatColors.sendButton
                                        else ChatColors.sendButtonDisabled,
                                        CircleShape,
                                    )
                                    .clip(CircleShape)
                                    .clickable(enabled = canActivate) {
                                        // T-drag-send-queue: route through the
                                        // shared send-or-enqueue handler. Same
                                        // semantics as before: slash short-
                                        // circuit, snapshot text, clear input
                                        // + focus, then sendMessage (which
                                        // routes to enqueuePrompt when
                                        // _isStreaming is true), then re-pin
                                        // the list to index 0 with a 100ms
                                        // re-pin to catch the late-mounting
                                        // "thinking" indicator.
                                        performSendOrEnqueue(inputText)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "Send",
                                    tint = if (canActivate) ChatColors.background
                                    else ChatColors.primaryText.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
                // --- Swipe-to-send floating hint (extracted helper) ---
                SwipeToSendHint(
                    progress = sendSwipeProgress,
                    armFraction = swipeArmFraction,
                    location = sendSwipeLocation,
                    hoverAbovePx = swipeHapticOffsetPx,
                    arrowHalfPx = swipeArrowHalfPx,
                    // While streaming, sendMessage() routes the prompt
                    // through enqueuePrompt() instead — surface that in
                    // the hint so the user knows the gesture still works
                    // mid-stream (mirrors the send-button's send/enqueue
                    // toggle, since on Android there's no separate visual
                    // state for the queued case).
                    isEnqueue = isStreaming,
                )
            } // end swipe-to-send Box wrapping the composer Column

            if (showMoveSheet) {
                MoveToSessionSheet(
                    currentSessionId = sessionId,
                    chatRepository = chatRepository,
                    onDismiss = { showMoveSheet = false },
                    onSelect = { targetId ->
                        ChatViewModelStore.stashPendingTransfer(
                            ChatViewModelStore.PendingTransfer(
                                inputText = inputText,
                                attachments = viewModel.attachments.value,
                            ),
                        )
                        viewModel.setInputText("")
                        viewModel.clearAttachments()
                        viewModel.clearShareInjectedFlag()
                        showMoveSheet = false
                        onMoveToSession(targetId)
                    },
                )
            }

            // T137: Clear Chat confirmation. Wipes messages + agent history +
            // compact markers; the session row, workspace files, attachments,
            // and offload payloads are intentionally preserved (iOS parity).
            if (showClearChatDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showClearChatDialog = false },
                    title = stringResource(R.string.chat_menu_clear_chat),
                    text = stringResource(R.string.chat_clear_dialog_body),
                    confirmText = stringResource(R.string.chat_clear_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.clearChat()
                        viewModel.setInputText("")
                        showClearChatDialog = false
                    },
                )
            }
            // [T-android-enhanced-cache] One-time extra-billing confirmation
            // before the first enable. Accepting records the durable ack and
            // turns the toggle on; subsequent enables skip the dialog.
            if (showEnhancedCacheDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showEnhancedCacheDialog = false },
                    title = stringResource(R.string.chat_menu_enhanced_cache),
                    text = stringResource(R.string.enhanced_cache_dialog_body),
                    confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
                    onConfirm = {
                        viewModel.confirmAndEnableEnhancedCache()
                        showEnhancedCacheDialog = false
                    },
                )
            }
        }
        // Top gradient fade: messages fade into the Scaffold background.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            ChatColors.background,
                            ChatColors.background.copy(alpha = 0f),
                        ),
                    )
                )
        )
        }
    }
    }

    // Browser bottom sheet
    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    // Session Token Usage bottom sheet
    if (showTokenUsageSheet) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheet = false },
        )
    }

    // Input history sheet — lists all messages in the current session so the
    // user can jump back to a specific prior input (or reply). Reuses the
    // same pendingFocusId + highlightedMessageId mechanism as session-switch
    // deep links, so selecting an entry scrolls to it and briefly highlights it.
    if (showInputHistorySheet) {
        InputHistorySheet(
            messages = messages,
            onSelect = { messageId ->
                showInputHistorySheet = false
                pendingFocusId = messageId
            },
            onDismiss = { showInputHistorySheet = false },
        )
    }

    // [bottom-toolbar-customizable] Export format picker — shared by the "..."
    // menu EXPORT entry and the history-drawer footer EXPORT action. The old
    // inline JSON/Plain submenu lived only in the menu; the sheet keeps the
    // format choice reachable from both entry points with one implementation.
    if (showExportFormatSheet) {
        ExportFormatSheet(
            onDismiss = { showExportFormatSheet = false },
            onExport = { format ->
                showExportFormatSheet = false
                exportCurrentChat(context, viewModel, chatRepository, coroutineScope, format)
            },
        )
    }

    // Memory bottom sheet
    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
        )
    }

    // Session Skills bottom sheet
    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }

    // [T-mcp-integration-android] MCPs-in-Session sheet.
    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }

    // [T-android-thinking-badge-navbar] Thinking-level sheet opened by tapping
    // the navbar thinking badge. Mirrors iOS ThinkingLevelSheetView: an Off row
    // plus every level the current model supports, each selectable.
    if (showThinkingLevelSheet) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsState()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheet = false
            },
            onDismiss = { showThinkingLevelSheet = false },
        )
    }

    // Model Picker bottom sheet
    if (showModelPicker) {
        val config by providerRepository.config.collectAsState()
        val activeEntryId by viewModel.activeEntryId.collectAsState()

        // When the user picks a model whose output is image/audio/video, defer
        // the actual binding behind a confirmation dialog — those models can't
        // drive an Agent loop, so we steer the user toward a text-output model
        // (or, if they really want it, hint at adding it as a tool inside an
        // Agent loop instead).
        var pendingNonTextSelection by remember {
            mutableStateOf<PendingNonTextSelection?>(null)
        }
        val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
        val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
        val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
        fun nonTextLabelFor(model: LLMModel): String? {
            val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
            return when {
                "image" in mods -> resolveImageLabel
                "audio" in mods -> resolveAudioLabel
                "video" in mods -> resolveVideoLabel
                else -> null
            }
        }
        fun entryById(entryId: String): ModelEntry? =
            config.modelEntries.firstOrNull { it.id == entryId }

        ModelPickerSheet(
            groups = availableGroups,
            selectedGroupId = selectedGroupId,
            activeEntryId = activeEntryId,
            defaultPrimaryGroupId = config.defaultPrimaryGroupId,
            config = config,
            providerRepository = providerRepository,
            onSelectGroup = { groupId ->
                val group = availableGroups.firstOrNull { it.id == groupId }
                val firstEntry = group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)
                val label = firstEntry?.model?.let(::nonTextLabelFor)
                if (label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Group(
                        groupId = groupId,
                        modelDisplayName = firstEntry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroup(groupId)
                    showModelPicker = false
                }
            },
            onSelectGroupEntry = { groupId, entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.GroupEntry(
                        groupId = groupId,
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroupEntry(groupId, entryId)
                    showModelPicker = false
                }
            },
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Entry(
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectEntry(entryId)
                    showModelPicker = false
                }
            },
            onDismiss = { showModelPicker = false },
            // [T-android-modelpicker-group-edit] Close the picker first, then
            // navigate — pushing the management screen on top of an open bottom
            // sheet leaves the sheet lingering behind it on back.
            onEditGroups = {
                showModelPicker = false
                onModelGroupsClick()
            },
        )

        pendingNonTextSelection?.let { pending ->
            MinisAlertDialog(
                onDismissRequest = { pendingNonTextSelection = null },
                title = stringResource(R.string.model_picker_non_text_warning_title),
                text = stringResource(
                    R.string.model_picker_non_text_warning_body,
                    pending.modelDisplayName,
                    pending.modalityLabel,
                    pending.modalityLabel,
                ),
                confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
                dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
                onConfirm = {
                    when (val sel = pending) {
                        is PendingNonTextSelection.Group -> viewModel.selectGroup(sel.groupId)
                        is PendingNonTextSelection.GroupEntry ->
                            viewModel.selectGroupEntry(sel.groupId, sel.entryId)
                        is PendingNonTextSelection.Entry -> viewModel.selectEntry(sel.entryId)
                    }
                    pendingNonTextSelection = null
                    showModelPicker = false
                },
            )
        }
    }

    // Offload permission dialog
    OffloadPermissionDialog()

    // URL preview sheet — shown when a markdown link is tapped
    previewUrl?.let { url ->
        com.openminis.app.ui.components.UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }

    // T146: immersive HTML preview — bottom sheet (90% height) by default,
    // with a Fullscreen button that swaps to a Dialog-based fullscreen
    // surface using the SAME WebViewHolder so the page never reloads.
    htmlPreviewHolder?.let { holder ->
        val onFullDismiss = {
            holder.destroy()
            htmlPreviewHolder = null
            htmlPreviewFallbackTitle = ""
            htmlPreviewFullscreen = false
        }
        if (htmlPreviewFullscreen) {
            com.openminis.app.ui.preview.WebPreviewFullscreenScreen(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                onCollapseToSheet = {
                    holder.detach()
                    htmlPreviewFullscreen = false
                },
                onDismiss = onFullDismiss,
            )
        } else {
            com.openminis.app.ui.preview.WebPreviewBottomSheet(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                pinSessionId = sessionId,
                onExpandFullscreen = {
                    holder.detach()
                    htmlPreviewFullscreen = true
                },
                onDismiss = onFullDismiss,
            )
        }
    }

    // T279: sandbox file preview is now routed through the NavHost
    // FILE_PREVIEW destination via onPreviewAttachment (see line ~1103),
    // matching how user-bubble attachments and "Browse Chat Files" already work.
    // The old in-place Dialog wrapper here was the source of the gray
    // status/nav bars — a Compose Dialog creates its own Window that
    // doesn't inherit MainActivity's enableEdgeToEdge, so the platform
    // default scrim painted over the bars regardless of what
    // FilePreviewScreen itself did.

    // Fullscreen image gallery — tapped image link from chat markdown or
    // composer chip. Pager-backed so multi-image messages support iOS-
    // style swipe between images. Single-image case is a 1-item list.
    previewImageGallery?.let { (items, startIdx) ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = { previewImageGallery = null },
        )
    }

    // Fullscreen video player — tapped video link (mp4/mov/m4v/…) from chat
    // markdown. Reuses the same dialog player as the markdown-rendered
    // ![](minis://...) syntax so behaviour is consistent regardless of how
    // the LLM emitted the reference.
    previewVideoFile?.let { file ->
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { previewVideoFile = null },
        )
    }

    // T-pwa-2: Add-to-Home-Screen sheet, hosted at screen level so it can
    // outlive the chip that triggered it (the chip Box may scroll out of
    // composition while the sheet is up).
    webAppSheetTarget?.let { target ->
        com.openminis.app.webapp.AddToHomeSheet(
            source = com.openminis.app.webapp.WebAppSource.ChatAttachment(
                uri = target.uri,
                fileName = target.fileName,
                sessionId = sessionId,
                sessionTitle = null,
            ),
            onDismiss = { webAppSheetTarget = null },
        )
    }
    } // CompositionLocalProvider
}

// [T-android-split-chat] UserMessageBubble / UserAttachmentList /
// FileAttachmentTile / fileIconFor / ImageGalleryDialog moved verbatim to
// ChatUserMessageUI.kt.
// [T-android-split-chat] FlatChatItem / mergeStreamingOverlay / buildFlatChatItems
// moved verbatim to ChatFlatItems.kt (now internal).

// [T-android-split-chat] AssistantHeader / AssistantMessageView /
// BoundsTrackedBlock / InlineErrorBanner / ToolStopButton /
// formatToolDetailsForClipboard / ToolCallPill / ThinkingBlock moved verbatim
// to ChatAssistantMessageUI.kt.

// ─── Tool Detail Bottom Sheet (iOS: ToolLiveSheet — nav bar + content + bottom bar) ──

// [T-android-split-chat] ToolDetailSheet + helpers (extractShellCommand,
// extractPartialJsonString, chunkToolOutput, initialRevealChunks,
// LazyRevealToolText, EditorCard) moved verbatim to ChatToolDetailUI.kt.
// [T-android-split-chat] AttachmentChip / InputCircleButton / MicButton /
// ToolPreviewThumbnail / FloatingToolStatusBar / ThinkingLevelPicker moved
// verbatim to ChatComposerWidgets.kt.

// [T-android-split-chat] createCameraOutputUri / getFileName /
// PendingNonTextSelection moved verbatim to ChatScreenHelpers.kt (now internal).


// [T-android-split-chat] fuzzyMatch / ModelPickerSheet / providerDotColor moved
// verbatim to ChatModelPickerSheet.kt (ModelPickerSheet now internal).

// [T-android-split-chat] BorderedMarkdownTable / FallbackInfoBlock /
// CompactSummarySheet / parseInlineMarkdown / rememberBrowserLiveSnapshot /
// ResumeBanner / SwipeToSendHint moved verbatim to ChatMiscViews.kt.
// Sun May 24 11:01:25 CST 2026

/**
 * [T-android-thinking-badge-navbar] Compact thinking-level pill shown on the
 * navbar's "provider · model" line (iOS AIChatView.thinkingLevelBadge parity).
 *
 * Deliberately smaller than the 11sp model-name text next to it — a 9dp
 * lightbulb + 9sp level label — so it reads as secondary auxiliary info and
 * never crowds out the model name. Uses [Icons.Default.Lightbulb], the same
 * glyph the `/thinking` slash command uses.
 *
 * Colors mirror iOS AIChatView.thinkingLevelBadge exactly — a NEUTRAL look, not
 * an accent one. iOS uses `foregroundStyle(secondaryText)` on a
 * `Capsule().fill(Color.secondary.opacity(0.10))` background; the Compose
 * equivalents are `onSurfaceVariant` (secondary grey) for the icon+label and
 * `onSurface.copy(alpha = 0.08f)` (a faint translucent grey) for the capsule.
 * We deliberately do NOT use `primary` / `primaryContainer` / the app's blue
 * thinking accent here: the badge is passive status ("thinking is on, at this
 * level"), not a call-to-action, so a blue highlight would over-emphasize it
 * and clash with the grey "provider · model" text it sits beside. Both colors
 * are theme tokens, so the badge adapts to light/dark automatically.
 *
 * The caller only mounts this when thinking is enabled, so the label is always
 * a real level (never "Off").
 *
 * It carries its OWN clickable (which consumes the tap) so a tap on the badge
 * opens the thinking-level sheet instead of the model picker owned by the
 * enclosing subtitle Column — see the call site for the full gesture-separation
 * rationale.
 */
@Composable
private fun ThinkingLevelBadge(
    level: com.openminis.app.data.model.ThinkingLevel,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Secondary grey for icon + label (iOS secondaryText parity) — no accent.
    val badgeColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            // Faint translucent-grey capsule (iOS Color.secondary.opacity(0.10)).
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            // Own clickable → consumes the tap, opens the thinking sheet.
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(9.dp),
        )
        Text(
            text = level.localizedName(context),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = badgeColor,
            maxLines = 1,
        )
    }
}

/**
 * [T-android-thinking-badge-navbar] Bottom-sheet thinking-level selector opened
 * from [ThinkingLevelBadge]. Mirrors iOS ThinkingLevelSheetView: an Off row
 * followed by every level the current model supports; the active level shows a
 * trailing check. Selecting any row calls [onSelect] (which also dismisses).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelSheet(
    currentLevel: com.openminis.app.data.model.ThinkingLevel,
    availableLevels: List<com.openminis.app.data.model.ThinkingLevel>,
    onSelect: (com.openminis.app.data.model.ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    // Off is always offered (turns thinking off); availableLevels already
    // excludes Off, so prepend it. De-dup defensively in case a caller ever
    // includes it.
    val rows = remember(availableLevels) {
        listOf(com.openminis.app.data.model.ThinkingLevel.OFF) +
            availableLevels.filter { it != com.openminis.app.data.model.ThinkingLevel.OFF }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.thinking_level_sheet_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)
            val context = LocalContext.current
            rows.forEach { level ->
                // "Off selected" = the current level is disabled; otherwise an
                // exact match.
                val isSelected = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) {
                    !currentLevel.isEnabled
                } else {
                    currentLevel == level
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(level) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = ChatColors.thinking.copy(
                            alpha = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) 0.4f else 1f,
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = level.localizedName(context),
                        fontSize = 15.sp,
                        color = ChatColors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = ChatColors.thinking,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

// ─── Export Current Conversation ────────────────────────────────────────────

/**
 * Export the active conversation via the streaming [com.openminis.app.share.ChatExporter]
 * — the same pipeline the session list's long-press Export uses (paginated
 * reads, staged zip, FileProvider share sheet), so long chats stay
 * bounded-memory. Draft sessions ("__new__" aliases, no DB row yet) get a
 * toast instead of an empty archive.
 */
private fun exportCurrentChat(
    context: android.content.Context,
    viewModel: ChatViewModel,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    format: String,
) {
    scope.launch {
        val sid = viewModel.activeSessionId
        if (sid.startsWith("__new__")) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_empty_hint),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return@launch
        }
        val session = chatRepository.observeSessions().first().firstOrNull { it.id == sid }
        if (session == null) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        try {
            val (uri, _) = com.openminis.app.share.ChatExporter.exportToZip(
                context = context,
                session = session,
                repository = chatRepository,
                format = format,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, session.title ?: "Conversation")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.sessionlist_export),
            ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
