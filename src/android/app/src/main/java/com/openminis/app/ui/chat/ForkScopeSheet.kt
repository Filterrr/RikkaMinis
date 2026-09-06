package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.History
import com.openminis.app.R
import androidx.compose.material.icons.automirrored.filled.ListAlt

/**
 * [T-message-fork-polish] Scope picker shown when the user long-presses →
 * "Branch from here": choose how much history the fork carries.
 *
 * Deliberately lightweight (a ModalBottomSheet with two rows, mirroring
 * ExportFormatSheet's shape): the fork's core UX is long-press → jump, so
 * the picker must read as a quick confirm, not a form. Selecting a row
 * fires [onScope] immediately and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForkScopeSheet(
    onDismiss: () -> Unit,
    onScope: (ForkScope) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.fork_scope_title),
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            ForkScopeRow(
                icon = Icons.Default.CallSplit,
                title = stringResource(R.string.fork_scope_all),
                subtitle = stringResource(R.string.fork_scope_all_desc),
                onClick = { onScope(ForkScope.All) },
            )
            ForkScopeRow(
                icon = Icons.Default.History,
                title = stringResource(R.string.fork_scope_last5),
                subtitle = stringResource(R.string.fork_scope_last5_desc),
                onClick = { onScope(ForkScope.LastTurns(turns = 5)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ForkScopeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.padding(end = 16.dp),
        )
        Column {
            Text(
                text = title,
                fontSize = 16.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
