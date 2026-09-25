package net.raphaelgf11.ilo3manager.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * A radio button whose whole row is the target, with an optional line of explanation under it.
 *
 * The label alone is a small target next to the button; making the row selectable is what the
 * platform does, and matters most on the lists of choices where the options differ by one word.
 */
@Composable
fun RadioRow(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
    detail: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Null: the row already handles the click, and a nested one would double the semantics.
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
