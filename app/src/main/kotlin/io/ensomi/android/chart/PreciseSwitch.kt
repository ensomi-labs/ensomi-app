package io.ensomi.android.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.ensomi.android.R

/**
 * Precise difficulty, off unless the player turns it on: make up to [PreciseSearch.MAX_CHARTS] charts and keep the
 * one closest to the stars asked for. The whole row is the switch, for a finger, for TalkBack and for a keyboard's
 * Space or Enter; the switch drawn at its end only shows the state.
 */
@Composable
fun PreciseSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.make_precise), style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.make_precise_help, PreciseSearch.MAX_CHARTS, PreciseSearch.MAX_CHARTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
