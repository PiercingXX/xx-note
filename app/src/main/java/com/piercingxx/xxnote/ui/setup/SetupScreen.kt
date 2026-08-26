package com.piercingxx.xxnote.ui.setup

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * WS6 first-run wizard (design §12): host/port → account → test → browse →
 * confirm + import disclosure → device name → first sync. Every step shows
 * its actual state in plain words (R10); nothing looks configured before it
 * is. Signature is final: [onConfigured] fires once the first sync lands
 * and the operator taps through.
 */
@Composable
fun SetupScreen(onConfigured: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SetupViewModel = viewModel { SetupViewModel(app) }
    val s by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ink)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepHeader(s.step)
        StatusLines(s.message, color = Tokens.White80)

        when (s.step) {
            SetupStep.HOST -> {
                Hint("WebDAV over HTTPS only — DSM's default HTTPS port is 5006.")
                Field("host, like nas.tailnet.ts.net", s.host, vm::editHost)
                Field("port", s.port, vm::editPort, keyboardType = KeyboardType.Number)
                NavRow(onNext = vm::continueHost, nextLabel = "continue")
            }

            SetupStep.ACCOUNT -> {
                Hint("use a dedicated xxnote account — WebDAV permission only, scoped to the notes folder (D15)")
                Field("username", s.user, vm::editUser)
                Field("password", s.password, vm::editPassword, hideInput = true)
                NavRow(onBack = vm::back, onNext = vm::continueAccount, nextLabel = "test connection")
            }

            SetupStep.TEST -> {
                MonoLines(s.probeLines)
                NavRow(onBack = vm::back, onNext = vm::continueTest, nextLabel = "pick folder")
            }

            SetupStep.FOLDER -> {
                Hint("where the notes live on the share — pick one that exists, or type a new path to create")
                s.folderRows.forEach { row ->
                    FolderPickerRow(row, enabled = !s.busy) { vm.pickExisting(row.path) }
                }
                Field("new folder, like Drive/Notes", s.newFolder, vm::editNewFolder)
                NavRow(
                    onBack = vm::back,
                    onNext = vm::createTypedFolder,
                    nextLabel = "create folder",
                    nextEnabled = !s.busy && s.newFolder.isNotBlank(),
                )
            }

            SetupStep.CONFIRM -> {
                Text(
                    SetupLogic.disclosureText(s.foundMd),
                    style = TextStyle(fontFamily = SpaceMono, fontSize = 15.sp, color = Tokens.White90),
                )
                StatusLines(
                    listOfNotNull(
                        SetupLogic.idlessLine(s.foundMd, s.idLessMd),
                        SetupLogic.etagLine(s.etagMode, s.hasWeakEtags),
                        "${SetupLogic.displayPath(s.pickedPath.orEmpty())} will hold the vault",
                        SetupLogic.subfolderLine(s.hasSubfolders),
                    ),
                    color = Tokens.White50,
                )
                NavRow(onBack = vm::back, onNext = vm::continueConfirm, nextLabel = "looks right")
            }

            SetupStep.DEVICE -> {
                Hint("names this device inside conflict filenames and on the sync screen")
                Field("device name", s.deviceName, vm::editDeviceName)
                NavRow(onBack = vm::back, onNext = vm::continueDevice, nextLabel = "continue")
            }

            SetupStep.FIRST_SYNC -> {
                Hint("the configuration is stored before syncing starts — an interrupted import stays configured and resumes")
                MonoLines(s.syncLines, color = Tokens.White90)
                if (!s.done) {
                    Button(
                        onClick = vm::startFirstSync,
                        enabled = !s.busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = primaryColors(),
                    ) {
                        Text("run first sync", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp))
                    }
                } else {
                    Button(
                        onClick = onConfigured,
                        modifier = Modifier.fillMaxWidth(),
                        colors = primaryColors(),
                    ) {
                        Text("open notes", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp))
                    }
                }
                NavRow(onBack = vm::back, backEnabled = !s.done && !s.busy)
            }
        }

        if (s.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Tokens.White90,
                trackColor = Tokens.White10,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- pieces ------------------------------------------------------------------

@Composable
private fun StepHeader(step: SetupStep) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "SETUP ${step.index}/7",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.White50),
        )
        Text(
            step.label.uppercase(),
            style = TextStyle(fontFamily = SpaceMono, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Tokens.White90),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.White50),
    )
}

/** R10 status lines: plain words for exactly where things stand. */
@Composable
private fun StatusLines(lines: List<String>, color: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            Text(line, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = color))
        }
    }
}

/** Verbatim output block (HTTP status lines, tally lines) in mono numerals. */
@Composable
private fun MonoLines(lines: List<String>, color: androidx.compose.ui.graphics.Color = Tokens.White80) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            Text(line, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = color))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    hideInput: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            // L11: a hidden field must also ask the IME for the password
            // layout — no autocomplete/suggestions over the secret.
            keyboardType = if (hideInput) KeyboardType.Password else keyboardType,
        ),
        visualTransformation = if (hideInput) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Tokens.White90,
            unfocusedTextColor = Tokens.White90,
            cursorColor = Tokens.White90,
            focusedBorderColor = Tokens.White50,
            unfocusedBorderColor = Tokens.White25,
            focusedLabelColor = Tokens.White50,
            unfocusedLabelColor = Tokens.White25,
        ),
    )
}

@Composable
private fun FolderPickerRow(row: FolderRow, enabled: Boolean, onPick: () -> Unit) {
    val background = if (row.reachable) Tokens.Slate else Tokens.Ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = enabled && row.reachable, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            SetupLogic.displayPath(row.path),
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White90),
        )
        Text(
            if (row.reachable) "${row.mdCount} .md" else row.note,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.White50),
        )
    }
}

/**
 * Back/next footer. Back is null only on the first step; next is hidden when
 * [onNext] is omitted (the first-sync step owns its own buttons).
 */
@Composable
private fun NavRow(
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    onNext: (() -> Unit)? = null,
    nextLabel: String = "continue",
    nextEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, enabled = backEnabled) {
                Text("back", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50))
            }
        } else {
            Spacer(Modifier.height(1.dp))
        }
        if (onNext != null) {
            Button(onClick = onNext, enabled = nextEnabled, colors = primaryColors()) {
                Text(nextLabel, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp))
            }
        } else {
            Spacer(Modifier.height(1.dp))
        }
    }
}

/** Strong emphasis inverts: white block, ink text (design §12.1). */
@Composable
private fun primaryColors() = ButtonDefaults.buttonColors(
    containerColor = Tokens.Signal,
    contentColor = Tokens.Ink,
    disabledContainerColor = Tokens.Graphite,
    disabledContentColor = Tokens.White50,
)
