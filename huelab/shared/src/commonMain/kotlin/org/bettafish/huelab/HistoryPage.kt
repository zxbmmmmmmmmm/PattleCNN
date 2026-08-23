package org.bettafish.huelab

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun HistoryScreen(
    controller: AppController,
    onBack: () -> Unit,
    onEdit: (HistoryRecord) -> Unit,
) {
    val state by controller.history.collectAsState()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回标注")
            }
            Header("标记历史", "共 ${state.records.size} 条已加载记录", Modifier.weight(1f))
            IconButton(controller::logout) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出登录")
            }
        }
        if (state.loading) LoadingScreen("正在读取历史…")
        else if (state.records.isEmpty()) EmptyState("还没有提交过标注", state.error) { controller.loadHistory(true) }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.records, key = { it.imageId }) { record ->
                LaunchedEffect(record.imageId) { controller.loadHistoryThumbnail(record.imageId) }
                HistoryRow(record, state.thumbnails[record.imageId]) { onEdit(record) }
            }
            item {
                if (state.page < state.totalPages) Button({ controller.loadHistory() }, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                    if (state.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("加载更多")
                }
            }
        }
        state.error?.let { ErrorText(it) }
    }
}

@Composable
private fun HistoryRow(record: HistoryRecord, thumbnail: ImageBitmap?, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(12.dp).height(88.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (thumbnail == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Image(thumbnail, record.imageName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(record.imageName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth()) { record.colors.forEach { Box(Modifier.weight(1f).height(30.dp).background(it.composeColor())) } }
            }
            Icon(Icons.Default.Edit, contentDescription = "编辑标注", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun HistoryEditor(
    editor: HistoryEditorState,
    controller: AppController,
    shaderSource: String,
    shaderError: String?,
    onLoadShader: () -> Unit,
    onResetShader: () -> Unit,
    onShaderResult: (String?) -> Unit,
    onBack: () -> Unit,
) {
    var previewHeld by remember(editor.record.imageId) { mutableStateOf(false) }
    var pickingColor by remember(editor.record.imageId) { mutableStateOf(false) }
    FullscreenEditor(
        title = editor.record.imageName,
        subtitle = buildString {
            append("修改历史标注")
            if (editor.saved) append(" · 已保存")
            append(" · 此页面不可横滑")
        },
        colors = editor.colors,
        selectedColor = editor.selectedColor,
        onSelectColor = controller::selectHistoryColor,
        onChangeColor = controller::updateHistoryColor,
        onResetPalette = controller::resetHistoryPalette,
        shaderSource = shaderSource,
        shaderError = shaderError,
        onLoadShader = onLoadShader,
        onResetShader = onResetShader,
        onShaderResult = onShaderResult,
        onHistory = null,
        onLogout = controller::logout,
        error = editor.error,
        onRetry = if (editor.image == null) ({ controller.openHistory(editor.record) }) else controller::saveHistory,
        onBack = onBack,
        controlsVisible = !previewHeld,
        pickingColor = pickingColor,
        onTogglePicking = { pickingColor = !pickingColor },
        toolbarAction = {
            IconButton(controller::saveHistory, enabled = editor.dirty && !editor.saving) {
                if (editor.saving) CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                else Icon(Icons.Default.Done, contentDescription = "保存修改")
            }
        },
    ) { paneModifier, imageScale ->
        if (editor.loading) LoadingPanel("正在加载历史图片…", paneModifier)
        else HistoryImageCard(
            editor,
            controller,
            paneModifier,
            imageScale = imageScale,
            pickingColor = pickingColor,
            previewOnly = previewHeld,
            onPreviewHoldChange = { previewHeld = it },
        )
    }
}

@Composable
private fun LoadingPanel(message: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = LocalContentColor.current)
            Text(message)
        }
    }
}

@Composable
private fun HistoryImageCard(
    editor: HistoryEditorState,
    controller: AppController,
    modifier: Modifier,
    imageScale: Float,
    pickingColor: Boolean,
    previewOnly: Boolean,
    onPreviewHoldChange: (Boolean) -> Unit,
) {
    editor.image?.let { image ->
        Box(modifier, contentAlignment = Alignment.Center) {
            SampleableImage(
                image,
                editor.record.imageName,
                pickingEnabled = pickingColor,
                showPickingIndicator = false,
                onSample = controller::sampleHistoryColor,
                onPreviewHoldChange = onPreviewHoldChange,
                modifier = Modifier.fillMaxSize(imageScale),
            )
        }
    } ?: EmptyState("图片无法显示", editor.error) { controller.openHistory(editor.record) }
}
