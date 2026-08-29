package org.bettafish.huelab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun AnnotationScreen(
    controller: AppController,
    shaderSource: String,
    shaderError: String?,
    onLoadShader: () -> Unit,
    onResetShader: () -> Unit,
    onShaderResult: (String?) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val state by controller.annotation.collectAsState()
    val item = state.current
    val pageCount by rememberUpdatedState(state.items.size + 1)
    val pagerState = rememberPagerState(
        initialPage = state.index.coerceAtLeast(0),
        pageCount = { pageCount },
    )
    var pickingColor by remember { mutableStateOf(false) }
    var previewHeld by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collectLatest(controller::settleGalleryPage)
    }
    LaunchedEffect(state.index) {
        pickingColor = false
        previewHeld = false
    }
    when {
        state.loading -> AnnotationFallback(
            controller, onLoadShader, onResetShader, onOpenHistory,
        ) { LoadingScreen("正在领取并分析图片…") }
        item == null -> AnnotationFallback(
            controller, onLoadShader, onResetShader, onOpenHistory,
        ) { EmptyState("暂时没有可标注图片", state.error, controller::retryTaskLoad) }
        else -> FullscreenEditor(
            title = item.name,
            subtitle = "第 ${state.index + 1} / ${state.items.size} 张 · ${item.uploadState.label} · ${item.expiresInSeconds / 60} 分钟",
            colors = item.colors,
            progress = item.progress,
            selectedColor = state.selectedColor,
            onSelectColor = controller::selectAnnotationColor,
            onChangeColor = controller::updateAnnotationColor,
            onResetPalette = controller::resetAnnotationPalette,
            shaderSource = shaderSource,
            shaderError = shaderError,
            onLoadShader = onLoadShader,
            onResetShader = onResetShader,
            onShaderResult = onShaderResult,
            onHistory = onOpenHistory,
            onLogout = controller::logout,
            error = item.uploadError ?: state.error,
            onRetry = if (item.uploadState == UploadState.Failed) controller::retryCurrentUpload else controller::retryTaskLoad,
            controlsVisible = !previewHeld,
            pickingColor = pickingColor,
            onTogglePicking = { pickingColor = !pickingColor },
        ) { paneModifier, imageScale ->
            PreviewColumn(
                state,
                pagerState,
                pickingColor,
                controller,
                paneModifier,
                imageScale = imageScale,
                previewOnly = previewHeld,
                onPreviewHoldChange = { previewHeld = it },
            )
        }
    }
}

private val UploadState.label: String
    get() = when (this) {
        UploadState.Idle -> "尚未提交"
        UploadState.Uploading -> "正在上传"
        UploadState.Uploaded -> "已上传"
        UploadState.Failed -> "上传失败"
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AnnotationFallback(
    controller: AppController,
    onLoadShader: () -> Unit,
    onResetShader: () -> Unit,
    onOpenHistory: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        TopAppBar(
            title = { Text("HueLab") },
            modifier = Modifier.fillMaxWidth(),
            actions = {
                EditorMoreMenu(
                    onHistory = onOpenHistory,
                    onLoadShader = onLoadShader,
                    onResetShader = onResetShader,
                    onLogout = controller::logout,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}
