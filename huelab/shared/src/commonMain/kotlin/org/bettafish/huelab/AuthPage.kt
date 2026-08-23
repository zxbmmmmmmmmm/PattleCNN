package org.bettafish.huelab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AuthScreen(state: SessionState.SignedOut, controller: AppController) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background),
            ),
        ).safeContentPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.width(420.dp), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("HueLab", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                Text("PattleCNN 四色训练数据标注台", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("登录", state.mode == AuthMode.Login, Modifier.weight(1f)) { controller.setAuthMode(AuthMode.Login) }
                    ModeButton("注册", state.mode == AuthMode.Register, Modifier.weight(1f)) { controller.setAuthMode(AuthMode.Register) }
                }
                OutlinedTextField(
                    state.username, controller::updateUsername, Modifier.fillMaxWidth(),
                    label = { Text("用户名") }, singleLine = true, enabled = !state.busy,
                )
                OutlinedTextField(
                    state.password, controller::updatePassword, Modifier.fillMaxWidth(),
                    label = { Text("密码") }, singleLine = true, enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                )
                state.error?.let { ErrorText(it) }
                Button(controller::authenticate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(if (state.mode == AuthMode.Login) "进入标注台" else "创建账户并进入")
                }
            }
        }
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick, modifier) { Text(text) } else OutlinedButton(onClick, modifier) { Text(text) }
}
