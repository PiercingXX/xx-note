package com.piercingxx.xxnote.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * Honest share-to-note block (P2): Setup unfinished, or a non-text file.
 * Does not write into a missing vault.
 */
@Composable
fun ShareBlockedScreen(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ink)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Share to XX-Note",
            style = TextStyle(fontFamily = SpaceMono, fontSize = 20.sp, color = Tokens.Signal),
        )
        Text(
            message,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 15.sp, color = Tokens.White90),
        )
        Spacer(Modifier.height(8.dp))
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tokens.Signal,
                    contentColor = Tokens.Ink,
                ),
            ) {
                Text(actionLabel, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp))
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(
                "close",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
            )
        }
    }
}
