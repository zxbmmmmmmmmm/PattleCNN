package org.bettafish.huelab

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

private val HueLabColors = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF49270A),
    primaryContainer = Color(0xFF653C17),
    secondary = Color(0xFFA8D7C7),
    background = Color(0xFF101412),
    surface = Color(0xFF171C19),
    surfaceVariant = Color(0xFF252B27),
    error = Color(0xFFFFB4AB),
)

@Serializable
internal sealed interface AppRoute : NavKey

@Serializable
internal data object AuthRoute : AppRoute

@Serializable
internal data object AnnotateRoute : AppRoute

@Serializable
internal data object HistoryRoute : AppRoute

@Serializable
internal data class HistoryEditorRoute(
    val imageId: String,
    val imageName: String,
    val colors: List<String>,
) : AppRoute {
    fun record(): HistoryRecord = HistoryRecord(imageId, imageName, colors.mapNotNull(RgbColor::parse))
}

internal val HueLabNavigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(AuthRoute::class, AuthRoute.serializer())
            subclass(AnnotateRoute::class, AnnotateRoute.serializer())
            subclass(HistoryRoute::class, HistoryRoute.serializer())
            subclass(HistoryEditorRoute::class, HistoryEditorRoute.serializer())
        }
    }
}

@Composable
@Preview
fun App() {
    val controller = remember { AppController() }
    DisposableEffect(controller) { onDispose(controller::dispose) }
    val session by controller.session.collectAsState()
    var shaderSource by remember { mutableStateOf(DEFAULT_SHADER_SOURCE) }
    var lastGoodShader by remember { mutableStateOf(DEFAULT_SHADER_SOURCE) }
    var shaderError by remember { mutableStateOf<String?>(null) }
    val picker = remember { createShaderFilePicker() }
    val coroutineScope = rememberCoroutineScope()

    MaterialTheme(colorScheme = HueLabColors) {
        Surface(Modifier.fillMaxSize()) {
            HueLabNavigation(
                controller = controller,
                session = session,
                shaderSource = shaderSource,
                shaderError = shaderError,
                onLoadShader = {
                    coroutineScope.launch {
                        picker.pickShaderSource().onSuccess { source ->
                            if (source != null) {
                                val validation = validateShaderSource(source)
                                if (validation == null) {
                                    shaderSource = source
                                    shaderError = null
                                } else shaderError = validation
                            }
                        }.onFailure { shaderError = it.message ?: "无法读取 Shader 文件" }
                    }
                },
                onResetShader = { shaderSource = DEFAULT_SHADER_SOURCE; shaderError = null },
                onShaderResult = { error ->
                    if (error == null) {
                        lastGoodShader = shaderSource
                        if (shaderError?.startsWith("Shader 编译失败") != true) shaderError = null
                    } else {
                        shaderError = "Shader 编译失败：$error"
                        if (shaderSource != lastGoodShader) shaderSource = lastGoodShader
                    }
                },
            )
        }
    }
}

@Composable
private fun HueLabNavigation(
    controller: AppController,
    session: SessionState,
    shaderSource: String,
    shaderError: String?,
    onLoadShader: () -> Unit,
    onResetShader: () -> Unit,
    onShaderResult: (String?) -> Unit,
) {
    val backStack = rememberNavBackStack(HueLabNavigationSavedStateConfiguration, AuthRoute)
    val editor by controller.historyEditor.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        when (session) {
            SessionState.Restoring -> Unit
            is SessionState.SignedOut -> if (backStack.lastOrNull() !is AuthRoute) {
                backStack.clear()
                backStack.add(AuthRoute)
            }
            SessionState.SignedIn -> if (backStack.lastOrNull() is AuthRoute) {
                backStack.clear()
                backStack.add(AnnotateRoute)
            }
        }
    }
    if (session == SessionState.Restoring) {
        LoadingScreen("正在恢复登录状态…")
        return
    }
    val navigateBack = {
        when (backStack.lastOrNull()) {
            is HistoryEditorRoute -> {
                if (controller.closeHistoryEditor()) backStack.removeLastOrNull()
                else confirmDiscard = true
            }
            else -> if (backStack.size > 1) backStack.removeLastOrNull()
        }
        Unit
    }
    NavDisplay(
        backStack = backStack,
        onBack = navigateBack,
        entryProvider = entryProvider {
            entry<AuthRoute> {
                val signedOut = session as? SessionState.SignedOut
                if (signedOut != null) AuthScreen(signedOut, controller)
                else LoadingScreen("正在进入标注台…")
            }
            entry<AnnotateRoute> {
                AnnotationScreen(
                    controller, shaderSource, shaderError, onLoadShader, onResetShader, onShaderResult,
                    onOpenHistory = {
                        controller.ensureHistoryLoaded()
                        if (backStack.lastOrNull() !is HistoryRoute) backStack.add(HistoryRoute)
                    },
                )
            }
            entry<HistoryRoute> {
                HistoryScreen(
                    controller = controller,
                    onBack = navigateBack,
                    onEdit = { record ->
                        controller.openHistory(record)
                        backStack.add(HistoryEditorRoute(record.imageId, record.imageName, record.colors.map { it.hex }))
                    },
                )
            }
            entry<HistoryEditorRoute> { route ->
                LaunchedEffect(route.imageId) {
                    if (editor?.record?.imageId != route.imageId) controller.openHistory(route.record())
                }
                val currentEditor = editor
                if (currentEditor == null) LoadingScreen("正在打开历史标注…")
                else HistoryEditor(
                    currentEditor, controller, shaderSource, shaderError, onLoadShader, onResetShader, onShaderResult,
                    onBack = navigateBack,
                )
            }
        },
    )
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存修改？") },
            text = { Text("当前颜色尚未重新上传，离开后修改会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    controller.closeHistoryEditor(true)
                    if (backStack.lastOrNull() is HistoryEditorRoute) backStack.removeLastOrNull()
                }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
}

@Composable
internal fun FullscreenEditor(
    title: String,
    subtitle: String,
    colors: List<RgbColor>,
    progress: AnnotationProgress? = null,
    selectedColor: Int,
    onSelectColor: (Int) -> Unit,
    onChangeColor: (RgbColor) -> Unit,
    onResetPalette: () -> Unit,
    shaderSource: String,
    shaderError: String?,
    onLoadShader: () -> Unit,
    onResetShader: () -> Unit,
    onShaderResult: (String?) -> Unit,
    onHistory: (() -> Unit)?,
    onLogout: () -> Unit,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    controlsVisible: Boolean = true,
    pickingColor: Boolean,
    onTogglePicking: () -> Unit,
    toolbarAction: @Composable RowScope.() -> Unit = {},
    imagePane: @Composable (Modifier, Float) -> Unit,
) {
    val contentColor = paletteContentColor(colors)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(Modifier.fillMaxSize()) {
            PlatformShaderPreview(colors, shaderSource, Modifier.fillMaxSize(), onShaderResult)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val topBarHeight = 64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val imageHeight = (maxHeight - topBarHeight) * .52f
                val imageScale by animateFloatAsState(
                    targetValue = if (pickingColor && controlsVisible) 1f else .5f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    label = "imageScale",
                )
                // Keep this invocation stable while long-press preview mode toggles so its
                // pointer gesture remains alive until the press is released.
                imagePane(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topBarHeight)
                        .fillMaxWidth()
                        .height(imageHeight),
                    imageScale,
                )
                if (controlsVisible) {
                    EditorTopAppBar(
                        title = title,
                        subtitle = subtitle,
                        contentColor = contentColor,
                        pickingColor = pickingColor,
                        onTogglePicking = onTogglePicking,
                        onHistory = onHistory,
                        onLoadShader = onLoadShader,
                        onResetShader = onResetShader,
                        onLogout = onLogout,
                        onBack = onBack,
                        toolbarAction = toolbarAction,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    ColorEditor(
                        colors = colors,
                        progress = progress,
                        selected = selectedColor,
                        onSelect = onSelectColor,
                        onChange = onChangeColor,
                        onReset = onResetPalette,
                        contentColor = contentColor,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(.42f)
                            .navigationBarsPadding(),
                        internallyScrollable = true,
                    )
                    val visibleError = error?.takeIf { onRetry != null } ?: shaderError
                    visibleError?.let {
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = topBarHeight),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .94f),
                        ) {
                            if (error != null && onRetry != null) ErrorBanner(error, onRetry)
                            else ErrorText(it, Modifier.padding(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopAppBar(
    title: String,
    subtitle: String,
    contentColor: Color,
    pickingColor: Boolean,
    onTogglePicking: () -> Unit,
    onHistory: (() -> Unit)?,
    onLoadShader: () -> Unit,
    onResetShader: () -> Unit,
    onLogout: () -> Unit,
    onBack: (() -> Unit)?,
    toolbarAction: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = modifier.fillMaxWidth(),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            }
        },
        actions = {
            toolbarAction()
            TextButton(
                onClick = onTogglePicking,
                colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
            ) {
                Icon(Icons.Default.Create, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (pickingColor) "完成" else "取色")
            }
            EditorMoreMenu(onHistory, onLoadShader, onResetShader, onLogout, contentColor)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            navigationIconContentColor = contentColor,
            titleContentColor = contentColor,
            actionIconContentColor = contentColor,
        ),
    )
}

@Composable
internal fun EditorMoreMenu(
    onHistory: (() -> Unit)?,
    onLoadShader: () -> Unit,
    onResetShader: () -> Unit,
    onLogout: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("更多")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onHistory != null) {
                DropdownMenuItem(
                    text = { Text("标记历史") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    onClick = { expanded = false; onHistory() },
                )
            }
            DropdownMenuItem(
                text = { Text("加载 Shader") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { expanded = false; onLoadShader() },
            )
            DropdownMenuItem(
                text = { Text("恢复内置 Shader") },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = { expanded = false; onResetShader() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("退出登录") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                onClick = { expanded = false; onLogout() },
            )
        }
    }
}

@Composable
internal fun PreviewColumn(
    state: AnnotationState,
    pagerState: PagerState,
    pickingColor: Boolean,
    controller: AppController,
    modifier: Modifier,
    imageScale: Float,
    previewOnly: Boolean,
    onPreviewHoldChange: (Boolean) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        pageSpacing = if (previewOnly) 0.dp else 8.dp,
        userScrollEnabled = !previewOnly && !pickingColor && !state.navigationBusy,
        flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = PagerSnapDistance.atMost(1),
        ),
    ) { page ->
        val pageItem = state.items.getOrNull(page)
        key(pageItem?.id ?: "loading-$page-${state.items.size}") {
            if (pageItem == null) {
                NextImagePlaceholder(state, controller)
            } else {
                val isCurrent = page == state.index
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SampleableImage(
                        pageItem.image,
                        pageItem.name,
                        pickingEnabled = pickingColor && isCurrent,
                        showPickingIndicator = false,
                        onSample = controller::sampleAnnotationColor,
                        onPreviewHoldChange = onPreviewHoldChange,
                        modifier = Modifier.fillMaxSize(imageScale),
                    )
                }
            }
        }
    }
}

@Composable
private fun NextImagePlaceholder(state: AnnotationState, controller: AppController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.navigationBusy) {
                CircularProgressIndicator()
                Text("正在提交当前标注并加载下一张…")
            } else {
                Text("下一张", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                state.error?.let { ErrorText(it) }
                Text("滑到这里后开始加载")
                if (state.error != null) OutlinedButton(controller::retryTaskLoad) { Text("重新加载") }
            }
        }
    }
}

@Composable
internal fun SampleableImage(
    image: ImageBitmap,
    contentDescription: String,
    pickingEnabled: Boolean,
    showPickingIndicator: Boolean,
    onSample: (Float, Float, Float, Float) -> Unit,
    onPreviewHoldChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gestureModifier = Modifier
        .pointerInput(image, pickingEnabled) {
            detectTapGestures(
                onPress = {
                    try {
                        tryAwaitRelease()
                    } finally {
                        onPreviewHoldChange(false)
                    }
                },
                onLongPress = { onPreviewHoldChange(true) },
                onTap = if (pickingEnabled) {
                    { point -> onSample(point.x, point.y, size.width.toFloat(), size.height.toFloat()) }
                } else null,
            )
        }
        .then(if (pickingEnabled) {
            Modifier
            .pointerInput(image) {
                detectDragGestures { change, _ ->
                    onSample(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat())
                    change.consume()
                }
            }
        } else Modifier)
    Box(
        modifier.onSizeChanged { size = it }.then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(image, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (pickingEnabled && showPickingIndicator) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = .9f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            ) { Text("取色模式", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ColorEditor(
    colors: List<RgbColor>, progress: AnnotationProgress?, selected: Int, onSelect: (Int) -> Unit, onChange: (RgbColor) -> Unit,
    onReset: () -> Unit, contentColor: Color, modifier: Modifier, internallyScrollable: Boolean,
) {
    val current = colors.getOrNull(selected) ?: return
    val hsv = current.toHsv()
    var hexInput by remember(current, selected) { mutableStateOf(current.hex) }
    val contentModifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).then(
        if (internallyScrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier,
    )
    val panelColor = if (contentColor == Color.Black) Color.White.copy(alpha = .46f) else Color.Black.copy(alpha = .42f)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(modifier.background(panelColor)) {
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = contentColor.copy(alpha = .18f),
                )
            }
            Column(contentModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                progress?.let {
                    Text(
                        "我的标注 ${it.currentUserMarkedCount}  ·  已标注 ${it.markedImageCount}  ·  总图片 ${it.totalImageCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor.copy(alpha = .82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("四色调节", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onReset, colors = ButtonDefaults.textButtonColors(contentColor = contentColor)) { Text("恢复 KMeans") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEachIndexed { index, color ->
                        Box(
                            Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(12.dp)).background(color.composeColor())
                                .then(if (selected == index) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                .pointerInput(index) { detectTapGestures { onSelect(index) } },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Text((index + 1).toString(), color = if (color.luminance > 150) Color.Black else Color.White, modifier = Modifier.padding(4.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("色相  ${hsv.hue.toInt()}°")
                Slider(hsv.hue, { onChange(RgbColor.fromHsv(it, hsv.saturation, hsv.value)) }, valueRange = 0f..360f)
                Text("饱和度  ${(hsv.saturation * 100).toInt()}%")
                Slider(hsv.saturation, { onChange(RgbColor.fromHsv(hsv.hue, it, hsv.value)) })
                Text("明度  ${(hsv.value * 100).toInt()}%")
                Slider(hsv.value, { onChange(RgbColor.fromHsv(hsv.hue, hsv.saturation, it)) })
                OutlinedTextField(
                    hexInput,
                    { value -> hexInput = value; RgbColor.parse(value)?.let(onChange) },
                    label = { Text("HEX") },
                    isError = RgbColor.parse(hexInput) == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = contentColor,
                        unfocusedTextColor = contentColor,
                        focusedLabelColor = contentColor,
                        unfocusedLabelColor = contentColor,
                        focusedBorderColor = contentColor,
                        unfocusedBorderColor = contentColor.copy(alpha = .7f),
                        cursorColor = contentColor,
                    ),
                )
            }
        }
    }
}

internal fun paletteContentColor(colors: List<RgbColor>): Color {
    val averageLuminance = colors.map(RgbColor::luminance).average()
    return if (averageLuminance.isNaN() || averageLuminance <= 145.0) Color.White else Color.Black
}

@Composable
internal fun Header(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LoadingScreen(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator()
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun EmptyState(title: String, detail: String?, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            detail?.let { ErrorText(it) }
            OutlinedButton(retry) { Text("重试") }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(retry) { Text("重试") }
        }
    }
}

@Composable
internal fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
