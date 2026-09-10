package com.openminis.app.ui.settings

import com.openminis.app.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.openminis.app.diagnostics.ProviderHealthTracker
import kotlinx.coroutines.delay

/**
 * [feat-provider-health] Provider Health panel.
 *
 * Turns the [ProviderHealthTracker] ring buffers (one per model, last 20
 * attempts, in-memory) into a readable table: recent TTFB P50/P95,
 * success rate over the recorded window, and the last failure reason.
 * Self-refreshes every 2s while visible so an ongoing run's stats tick
 * up live. Empty state explains the in-memory semantics (resets on
 * process death) so the panel never looks "broken" after a restart.
 */
@Composable
fun ProviderHealthScreen(
    onBack: () -> Unit,
) {
    var snapshot by remember { mutableStateOf(ProviderHealthTracker.snapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = ProviderHealthTracker.snapshot()
            delay(2000)
        }
    }

    SettingsScaffold(title = stringResource(R.string.provider_health_title), onBack = onBack) {
        if (snapshot.isEmpty()) {
            Text(
                text = stringResource(R.string.provider_health_empty),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            )
            return@SettingsScaffold
        }

        snapshot.forEach { health ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = health.modelDisplayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.provider_health_success_rate,
                            (health.successRate * 100).toInt(),
                        ),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            health.successRate >= 0.9 -> MaterialTheme.colorScheme.primary
                            health.successRate >= 0.5 -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.provider_health_ttfb,
                        health.ttfbP50Ms ?: -1L,
                        health.ttfbP95Ms ?: -1L,
                        health.attempts.size,
                    ),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                health.lastFailureReason?.let { reason ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.provider_health_last_failure, reason.take(120)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }

        Text(
            text = stringResource(R.string.provider_health_footer),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}
