package org.bettafish.huelab

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

enum class AuthMode { Login, Register }
enum class UploadState { Idle, Uploading, Uploaded, Failed }

sealed interface SessionState {
    data object Restoring : SessionState
    data class SignedOut(
        val mode: AuthMode = AuthMode.Login,
        val username: String = "",
        val password: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) : SessionState
    data object SignedIn : SessionState
}

data class AnnotationItem(
    val id: String,
    val name: String,
    val image: ImageBitmap,
    val initialColors: List<RgbColor>,
    val colors: List<RgbColor>,
    val expiresInSeconds: Int,
    val uploadState: UploadState = UploadState.Idle,
    val uploadError: String? = null,
)

data class AnnotationState(
    val items: List<AnnotationItem> = emptyList(),
    val index: Int = -1,
    val selectedColor: Int = 0,
    val loading: Boolean = false,
    val navigationBusy: Boolean = false,
    val error: String? = null,
) {
    val current: AnnotationItem? get() = items.getOrNull(index)
}

data class HistoryState(
    val records: List<HistoryRecord> = emptyList(),
    val thumbnails: Map<String, ImageBitmap> = emptyMap(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val totalPages: Int = 1,
)

data class HistoryEditorState(
    val record: HistoryRecord,
    val image: ImageBitmap? = null,
    val initialPalette: List<RgbColor> = record.colors,
    val colors: List<RgbColor> = record.colors,
    val selectedColor: Int = 0,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = colors != record.colors
}

class AppController(
    private val api: HueLabApi = HueLabApi(),
    private val imageCache: ImageCache = createImageCache(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val _session = MutableStateFlow<SessionState>(SessionState.Restoring)
    val session: StateFlow<SessionState> = _session.asStateFlow()
    private val _annotation = MutableStateFlow(AnnotationState())
    val annotation: StateFlow<AnnotationState> = _annotation.asStateFlow()
    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history.asStateFlow()
    private val _historyEditor = MutableStateFlow<HistoryEditorState?>(null)
    val historyEditor: StateFlow<HistoryEditorState?> = _historyEditor.asStateFlow()
    private val submitLocks = mutableMapOf<String, Mutex>()
    private val submitJobs = mutableMapOf<String, Job>()
    private var loadTaskJob: Job? = null

    init {
        scope.launch {
            val restored = runCatching { api.restoreSession() }.getOrDefault(false)
            _session.value = if (restored) SessionState.SignedIn else SessionState.SignedOut()
            if (restored) loadFirstTask()
        }
    }

    fun setAuthMode(mode: AuthMode) {
        val current = _session.value as? SessionState.SignedOut ?: return
        _session.value = current.copy(mode = mode, error = null)
    }

    fun updateUsername(value: String) = updateSignedOut { copy(username = value, error = null) }
    fun updatePassword(value: String) = updateSignedOut { copy(password = value, error = null) }

    fun authenticate() {
        val current = _session.value as? SessionState.SignedOut ?: return
        if (current.username.isBlank() || current.password.isBlank()) {
            _session.value = current.copy(error = "请输入用户名和密码")
            return
        }
        _session.value = current.copy(busy = true, error = null)
        scope.launch {
            runCatching {
                if (current.mode == AuthMode.Login) api.login(current.username, current.password)
                else api.register(current.username, current.password)
            }.onSuccess {
                _session.value = SessionState.SignedIn
                loadFirstTask()
            }.onFailure { error ->
                _session.value = current.copy(busy = false, error = error.userMessage())
            }
        }
    }

    fun logout() {
        scope.launch {
            runCatching { api.logout() }
            _session.value = SessionState.SignedOut()
            _annotation.value = AnnotationState()
            _history.value = HistoryState()
            _historyEditor.value = null
        }
    }

    fun ensureHistoryLoaded() {
        if (_history.value.records.isEmpty()) loadHistory(refresh = true)
    }

    fun selectAnnotationColor(index: Int) {
        _annotation.value = _annotation.value.copy(selectedColor = index.coerceIn(0, 3))
    }

    fun updateAnnotationColor(color: RgbColor) {
        val state = _annotation.value
        val item = state.current ?: return
        val changed = item.colors.toMutableList().also { it[state.selectedColor] = color }
        replaceAnnotation(item.copy(colors = changed, uploadState = UploadState.Idle, uploadError = null))
    }

    fun sampleAnnotationColor(viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float) {
        val item = _annotation.value.current ?: return
        sample(item.image, viewX, viewY, viewWidth, viewHeight)?.let(::updateAnnotationColor)
    }

    fun resetAnnotationPalette() {
        val item = _annotation.value.current ?: return
        replaceAnnotation(item.copy(colors = item.initialColors, uploadState = UploadState.Idle, uploadError = null))
    }

    fun previousImage() {
        val state = _annotation.value
        if (state.index <= 0 || state.navigationBusy) return
        submitIfNeeded(state.current ?: return)
        _annotation.value = _annotation.value.copy(index = state.index - 1, selectedColor = 0, error = null)
    }

    fun settleGalleryPage(page: Int) {
        val state = _annotation.value
        val current = state.current ?: return
        when {
            page in state.items.indices && page != state.index -> {
                submitIfNeeded(current)
                _annotation.value = _annotation.value.copy(index = page, selectedColor = 0, error = null)
            }
            page == state.items.size && !state.navigationBusy -> {
                loadNextTask(waitForCurrentSubmission = true)
            }
        }
    }

    fun nextImage() {
        val state = _annotation.value
        val current = state.current ?: return
        if (state.navigationBusy) return
        if (state.index < state.items.lastIndex) {
            submitIfNeeded(current)
            _annotation.value = _annotation.value.copy(index = state.index + 1, selectedColor = 0, error = null)
        } else {
            loadNextTask(waitForCurrentSubmission = true)
        }
    }

    fun retryCurrentUpload() {
        _annotation.value.current?.let(::submit)
    }

    fun retryTaskLoad() {
        if (_annotation.value.items.isEmpty()) loadFirstTask()
        else loadNextTask(waitForCurrentSubmission = true, retryFailedSubmission = true)
    }

    fun loadHistory(refresh: Boolean = false) {
        val state = _history.value
        if (state.loading || state.loadingMore) return
        val nextPage = if (refresh) 1 else state.page + 1
        if (!refresh && state.page >= state.totalPages) return
        _history.value = if (refresh) state.copy(loading = true, error = null) else state.copy(loadingMore = true, error = null)
        scope.launch {
            runCatching { api.history(nextPage) }
                .onSuccess { page ->
                    val records = page.items.mapNotNull { dto ->
                        val colors = dto.colors.mapNotNull(RgbColor::parse)
                        if (colors.size == 4) HistoryRecord(dto.imageId, dto.imageName, colors) else null
                    }
                    _history.value = HistoryState(
                        records = if (refresh) records else _history.value.records + records,
                        thumbnails = if (refresh) emptyMap() else _history.value.thumbnails,
                        page = page.page,
                        totalPages = page.totalPages ?: ((page.totalCount + page.pageSize - 1) / page.pageSize).coerceAtLeast(1),
                    )
                }.onFailure(::handleHistoryFailure)
        }
    }

    fun openHistory(record: HistoryRecord) {
        _historyEditor.value = HistoryEditorState(record)
        scope.launch {
            runCatching { _history.value.thumbnails[record.imageId] ?: loadImage(record.imageId) }.onSuccess { image ->
                _historyEditor.value = _historyEditor.value?.copy(image = image, loading = false)
                runCatching { extractPalette(image) }.onSuccess { palette ->
                    _historyEditor.value = _historyEditor.value?.takeIf { it.record.imageId == record.imageId }
                        ?.copy(initialPalette = palette)
                }
            }.onFailure { error ->
                _historyEditor.value = _historyEditor.value?.copy(loading = false, error = error.userMessage())
            }
        }
    }

    fun loadHistoryThumbnail(imageId: String) {
        if (_history.value.thumbnails.containsKey(imageId)) return
        scope.launch {
            runCatching { loadImage(imageId) }.onSuccess { bitmap ->
                _history.value = _history.value.copy(thumbnails = _history.value.thumbnails + (imageId to bitmap))
            }
        }
    }

    fun closeHistoryEditor(discardChanges: Boolean = false): Boolean {
        val editor = _historyEditor.value ?: return true
        if (editor.dirty && !discardChanges && !editor.saved) return false
        _historyEditor.value = null
        return true
    }

    fun selectHistoryColor(index: Int) {
        _historyEditor.value = _historyEditor.value?.copy(selectedColor = index.coerceIn(0, 3))
    }

    fun updateHistoryColor(color: RgbColor) {
        val editor = _historyEditor.value ?: return
        val changed = editor.colors.toMutableList().also { it[editor.selectedColor] = color }
        _historyEditor.value = editor.copy(colors = changed, error = null, saved = false)
    }

    fun sampleHistoryColor(viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float) {
        val editor = _historyEditor.value ?: return
        val image = editor.image ?: return
        sample(image, viewX, viewY, viewWidth, viewHeight)?.let(::updateHistoryColor)
    }

    fun resetHistoryPalette() {
        val editor = _historyEditor.value ?: return
        _historyEditor.value = editor.copy(colors = editor.initialPalette, saved = false, error = null)
    }

    fun saveHistory() {
        val editor = _historyEditor.value ?: return
        if (editor.saving) return
        _historyEditor.value = editor.copy(saving = true, error = null)
        scope.launch {
            runCatching { api.submitColors(editor.record.imageId, editor.colors) }
                .onSuccess {
                    _historyEditor.value = _historyEditor.value?.copy(
                        record = editor.record.copy(colors = editor.colors),
                        saving = false,
                        saved = true,
                    )
                    _history.value = _history.value.copy(records = _history.value.records.map {
                        if (it.imageId == editor.record.imageId) it.copy(colors = editor.colors) else it
                    })
                }.onFailure { error ->
                    _historyEditor.value = _historyEditor.value?.copy(saving = false, error = error.userMessage())
                    handleUnauthorized(error)
                }
        }
    }

    fun dispose() = scope.cancel()

    private fun loadFirstTask() {
        if (_annotation.value.loading) return
        _annotation.value = _annotation.value.copy(loading = true, error = null)
        loadTaskJob?.cancel()
        loadTaskJob = scope.launch {
            runCatching { requestAnnotation() }
                .onSuccess { _annotation.value = AnnotationState(items = listOf(it), index = 0) }
                .onFailure { error ->
                    _annotation.value = AnnotationState(error = error.userMessage())
                    handleUnauthorized(error)
                }
        }
    }

    private fun loadNextTask(
        waitForCurrentSubmission: Boolean = false,
        retryFailedSubmission: Boolean = false,
    ) {
        val state = _annotation.value
        if (state.navigationBusy) return
        val current = state.current
        _annotation.value = state.copy(navigationBusy = true, error = null)
        loadTaskJob = scope.launch {
            if (waitForCurrentSubmission && current != null) {
                submitIfNeeded(current, retryFailedSubmission)?.join()
                val latestCurrent = _annotation.value.items.firstOrNull { it.id == current.id }
                if (latestCurrent?.uploadState != UploadState.Uploaded) {
                    _annotation.value = _annotation.value.copy(
                        navigationBusy = false,
                        error = latestCurrent?.uploadError ?: "当前标注尚未成功上传，请重试后再切换",
                    )
                    return@launch
                }
            }
            runCatching { requestAnnotation() }
                .onSuccess { item ->
                    val latest = _annotation.value
                    _annotation.value = latest.copy(
                        items = latest.items + item,
                        index = latest.items.size,
                        selectedColor = 0,
                        navigationBusy = false,
                    )
                }.onFailure { error ->
                    _annotation.value = _annotation.value.copy(navigationBusy = false, error = error.userMessage())
                    handleUnauthorized(error)
                }
        }
    }

    private suspend fun requestAnnotation(): AnnotationItem {
        val task = api.claimTask()
        val image = loadImage(task.id, task.imageUrl)
        val colors = extractPalette(image)
        return AnnotationItem(task.id, task.name, image, colors, colors, task.expireSeconds)
    }

    private fun submitIfNeeded(snapshot: AnnotationItem, retryFailed: Boolean = false): Job? {
        val latest = _annotation.value.items.firstOrNull { it.id == snapshot.id } ?: snapshot
        return when (latest.uploadState) {
            UploadState.Idle -> submit(latest)
            UploadState.Uploading -> submitJobs[latest.id]
            UploadState.Failed -> if (retryFailed) submit(latest) else null
            UploadState.Uploaded -> null
        }
    }

    private fun submit(snapshot: AnnotationItem): Job {
        replaceAnnotation(snapshot.copy(uploadState = UploadState.Uploading, uploadError = null))
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val lock = submitLocks.getOrPut(snapshot.id) { Mutex() }
                lock.withLock {
                    runCatching { api.submitColors(snapshot.id, snapshot.colors) }
                        .onSuccess { updateUploadState(snapshot.id, snapshot.colors, UploadState.Uploaded, null) }
                        .onFailure { error ->
                            updateUploadState(snapshot.id, snapshot.colors, UploadState.Failed, error.userMessage())
                            handleUnauthorized(error)
                        }
                }
            } finally {
                if (submitJobs[snapshot.id] === job) submitJobs.remove(snapshot.id)
            }
        }
        submitJobs[snapshot.id] = job
        job.start()
        return job
    }

    private fun updateUploadState(id: String, submittedColors: List<RgbColor>, status: UploadState, error: String?) {
        val state = _annotation.value
        _annotation.value = state.copy(items = state.items.map { item ->
            if (item.id == id && item.colors == submittedColors) item.copy(uploadState = status, uploadError = error) else item
        })
    }

    private fun replaceAnnotation(item: AnnotationItem) {
        val state = _annotation.value
        _annotation.value = state.copy(items = state.items.map { if (it.id == item.id) item else it })
    }

    private fun sample(image: ImageBitmap, x: Float, y: Float, width: Float, height: Float): RgbColor? {
        val coordinate = ImageSampleTransform(image.width, image.height, width, height).sourceCoordinate(x, y) ?: return null
        val color = image.toPixelMap()[coordinate.first, coordinate.second]
        return RgbColor((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun decodeImage(bytes: ByteArray): ImageBitmap = withContext(Dispatchers.Default) {
        bytes.decodeToImageBitmap()
    }

    private suspend fun loadImage(imageId: String, preferredUrl: String? = null): ImageBitmap {
        imageCache.read(imageId)?.let { cached ->
            runCatching { decodeImage(cached) }.onSuccess { return it }
            runCatching { imageCache.remove(imageId) }
        }
        val downloaded = api.downloadImage(imageId, preferredUrl)
        val image = decodeImage(downloaded)
        scope.launch { runCatching { imageCache.write(imageId, downloaded) } }
        return image
    }

    private suspend fun extractPalette(image: ImageBitmap): List<RgbColor> = withContext(Dispatchers.Default) {
        val pixelMap = image.toPixelMap()
        val total = image.width * image.height
        // Ceiling division keeps KMeans input at or below the 128 x 128 target.
        val step = ((total + 16_383) / 16_384).coerceAtLeast(1)
        val pixels = buildList {
            var index = 0
            while (index < total) {
                val color = pixelMap[index % image.width, index / image.width]
                if (color.alpha >= 0.5f) add(RgbColor((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
                index += step
            }
        }
        KMeansPalette.extract(pixels)
    }

    private fun handleHistoryFailure(error: Throwable) {
        val state = _history.value
        _history.value = state.copy(loading = false, loadingMore = false, error = error.userMessage())
        handleUnauthorized(error)
    }

    private fun handleUnauthorized(error: Throwable) {
        if (error is ApiException && error.status == 401) _session.value = SessionState.SignedOut(error = error.message)
    }

    private fun updateSignedOut(transform: SessionState.SignedOut.() -> SessionState.SignedOut) {
        val current = _session.value as? SessionState.SignedOut ?: return
        _session.value = current.transform()
    }
}

private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
