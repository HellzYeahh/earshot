package dev.earshot.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.PairingRequest

/**
 * One-time pairing with headphones that require a secure session: the phone shows a code, the
 * headphones announce the same code (or ask for a button press), and the user confirms they match.
 * That comparison is what stops anyone else from taking control of the headphones.
 */
@Composable
fun PairingDialog(request: PairingRequest, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.pairing_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val code = request.passcode
                if (code != null) {
                    Text(stringResource(R.string.pairing_body_code), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        code.toString().padStart(request.digits, '0'),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                    )
                    Text(stringResource(R.string.pairing_body_match), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.pairing_body_gesture), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.pairing_match)) }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
