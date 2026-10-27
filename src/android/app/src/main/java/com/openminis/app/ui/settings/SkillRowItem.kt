package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.SkillRepository

/**
 * Shared list row for a single skill — used by both [SkillsManagementScreen]
 * (Settings → Skills) and the in-chat Skills-in-Session sheet so the visual
 * rhythm stays identical across both surfaces.
 *
 * Layout:
 *   ┌──────────────────────────────────────────────────────────┐
 *   │ name [src-icon]                       [switch] [chevron] │
 *   │ description (up to 2 lines, tertiary text)               │
 *   └──────────────────────────────────────────────────────────┘
 *
 * Pass [onClick] to make the row navigate (Settings page); leave null in
 * sheet contexts where the row is only a switch host.
 */
@Composable
fun SkillRowItem(
    name: String,
    description: String,
    importSource: SkillRepository.ImportSource,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    /**
     * [feat/subagent-roster-routing] True when this skill is a SUB-AGENT
     * (`subagent: true` in its frontmatter).
     *
     * The skills list mixed delegates with ordinary instruction sets and
     * gave no way to tell them apart. That is not cosmetic: a sub-agent
     * skill is a DELEGATE the parent can hand work to, and it carries a
     * permission set (`allowed_tools`) that the user is granting by
     * enabling it. Seeing the badge makes the difference legible, and it
     * is the anchor for the trust-management screen below.
     */
    isSubagent: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val (badgeIcon, badgeColor) = sourceIconAndColor(importSource)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(14.dp),
                    )
                    // [feat/subagent-roster-routing] The sub-agent badge.
                    // It sits NEXT TO the source badge, not instead of it —
                    // where a skill came from and what it IS are different
                    // questions.
                    if (isSubagent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.skill_subagent_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )

            if (onClick != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * Source-of-truth mapping for the small badge icon next to a skill's name.
 * Kept here (rather than duplicated in each screen) so badge colors and icons
 * stay consistent between Settings and the in-chat sheet.
 */
fun sourceIconAndColor(source: SkillRepository.ImportSource): Pair<ImageVector, Color> = when (source) {
    SkillRepository.ImportSource.URL -> Icons.Default.Link to Color(0xFF007AFF)
    SkillRepository.ImportSource.FILE -> Icons.Outlined.Description to Color(0xFFFF9500)
    SkillRepository.ImportSource.BUNDLED -> Icons.Default.Inventory2 to Color(0xFF34C759)
    SkillRepository.ImportSource.SESSION -> Icons.Outlined.ChatBubble to Color(0xFFAF52DE)
}
