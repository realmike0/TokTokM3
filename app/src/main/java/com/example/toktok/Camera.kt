@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, UnstableApi::class)
package com.example.toktok

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint as APaint
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.Context
import android.util.Range
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.compose.foundation.Canvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(onClose: () -> Unit, onCaptured: (Uri, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    fun ok() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(ok()) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = ok() }
    LaunchedEffect(Unit) { if (!granted) req.launch(perms) }
    if (granted) CameraContent(onClose, onCaptured) else
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Serve l'accesso a fotocamera e microfono"); Spacer(Modifier.height(16.dp))
            Button({ req.launch(perms) }) { Text("Consenti") }
            TextButton(onClose) { Text("Annulla") }
        }
}

suspend fun <T> ListenableFuture<T>.await(ctx: Context): T = suspendCancellableCoroutine { c ->
    addListener({ try { c.resume(get()) } catch (e: Exception) { c.resumeWithException(e) } }, ContextCompat.getMainExecutor(ctx))
}

private fun qName(q: Quality) = when (q) { Quality.UHD -> "4K"; Quality.FHD -> "1080p"; Quality.HD -> "720p"; else -> "SD" }
private fun extName(m: Int) = when (m) { ExtensionMode.AUTO -> "Auto"; ExtensionMode.HDR -> "HDR"; ExtensionMode.NIGHT -> "Notte"; ExtensionMode.FACE_RETOUCH -> "Ritratto"; else -> "Standard" }

@SuppressLint("MissingPermission")
@Composable
fun CameraContent(onClose: () -> Unit, onCaptured: (Uri, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var front by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf(false) }
    var f by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var rec by remember { mutableStateOf<Recording?>(null) }
    var secs by remember { mutableIntStateOf(0) }
    var cam by remember { mutableStateOf<Camera?>(null) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    var quality by remember { mutableStateOf(Quality.FHD) }
    var ext by remember { mutableIntStateOf(ExtensionMode.NONE) }
    var exts by remember { mutableStateOf(listOf(ExtensionMode.NONE)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var expTick by remember { mutableIntStateOf(0) }
    var showExp by remember { mutableStateOf(false) }
    var exp by remember { mutableFloatStateOf(0f) }
    var shutter by remember { mutableStateOf(false) }

    val pv = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    pv.scaleType = if (photo) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
    // Foto: 4:3 alla massima risoluzione del sensore. Video: 16:9.
    val rsPhoto = remember { ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build() }
    val rsVideo = remember { ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build() }
    val ic = remember { ImageCapture.Builder().setResolutionSelector(rsPhoto).setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(100).build() }
    val vc = remember(quality) {
        val bitrate = when (quality) { Quality.UHD -> 40_000_000; Quality.FHD -> 16_000_000; else -> 8_000_000 }
        VideoCapture.Builder(Recorder.Builder().setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).setTargetVideoEncodingBitRate(bitrate).build())
            .setVideoStabilizationEnabled(true).setTargetFrameRate(Range(30, 60)).build()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        u?.let { onCaptured(it, ctx.contentResolver.getType(it)?.startsWith("image") == true) } }

    LaunchedEffect(front, photo, quality, ext) {
        val prov = ProcessCameraProvider.getInstance(ctx).await(ctx)
        val base = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        var sel = base
        if (photo) runCatching {
            val em = ExtensionsManager.getInstanceAsync(ctx, prov).await(ctx)
            exts = listOf(ExtensionMode.NONE) + listOf(ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.FACE_RETOUCH).filter { em.isExtensionAvailable(base, it) }
            if (ext != ExtensionMode.NONE && em.isExtensionAvailable(base, ext)) sel = em.getExtensionEnabledCameraSelector(base, ext)
        }
        val pre = Preview.Builder().setResolutionSelector(if (photo) rsPhoto else rsVideo).build().also { it.surfaceProvider = pv.surfaceProvider }
        prov.unbindAll()
        cam = runCatching { if (photo) prov.bindToLifecycle(owner, sel, pre, ic) else prov.bindToLifecycle(owner, sel, pre, vc) }
            .getOrElse { Toast.makeText(ctx, "Fotocamera non disponibile con queste impostazioni", Toast.LENGTH_SHORT).show(); null }
        cam?.cameraInfo?.zoomState?.observe(owner) { zoom = it.zoomRatio }
        exp = 0f
    }
    val hasFlash = cam?.cameraInfo?.hasFlashUnit() == true
    LaunchedEffect(flash, cam, photo) {
        ic.flashMode = if (flash && photo) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        if (hasFlash) cam?.cameraControl?.enableTorch(flash && !photo)
    }
    LaunchedEffect(rec) { secs = 0; while (rec != null) { delay(1000); secs++ } }
    LaunchedEffect(focus) { if (focus != null) { delay(900); focus = null } }
    LaunchedEffect(expTick) { if (expTick > 0) { showExp = true; delay(3500); showExp = false } }
    LaunchedEffect(shutter) { if (shutter) { delay(140); shutter = false } }
    DisposableEffect(Unit) { onDispose { rec?.stop(); runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } } }

    val exec = ContextCompat.getMainExecutor(ctx)
    fun cv(mime: String, dir: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "TokTok_${System.currentTimeMillis()}"); put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.RELATIVE_PATH, dir) }
    fun doCapture() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when {
            photo -> {
                shutter = true
                val o = ImageCapture.OutputFileOptions.Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv("image/jpeg", "Pictures/TokTok"))
                    .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = front }).build()
                ic.takePicture(o, exec, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { r.savedUri?.let { onCaptured(it, true) } }
                    override fun onError(e: ImageCaptureException) { Toast.makeText(ctx, "Errore foto", Toast.LENGTH_SHORT).show() }
                })
            }
            rec == null -> rec = vc.output.prepareRecording(ctx, MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(cv("video/mp4", "Movies/TokTok")).setDurationLimitMillis(180_000).build())
                .withAudioEnabled().start(exec) { ev -> if (ev is VideoRecordEvent.Finalize) { rec = null; if (!ev.hasError()) onCaptured(ev.outputResults.outputUri, false) } }
            else -> rec?.stop()
        }
    }
    val zs = cam?.cameraInfo?.zoomState?.value
    val es = cam?.cameraInfo?.exposureState
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ pv }, Modifier.fillMaxSize()
            .pointerInput(cam) { detectTransformGestures { _, _, z, _ -> cam?.let { c ->
                val s = c.cameraInfo.zoomState.value ?: return@let
                c.cameraControl.setZoomRatio((s.zoomRatio * z).coerceIn(s.minZoomRatio, s.maxZoomRatio)) } } }
            .pointerInput(cam) { detectTapGestures { o ->
                focus = o; expTick++
                cam?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pv.meteringPointFactory.createPoint(o.x, o.y)).build()) } },
            update = { it.setLayerType(View.LAYER_TYPE_HARDWARE, APaint().apply { colorFilter = ColorMatrixColorFilter(filters[f].cm) }) })
        if (grid) Canvas(Modifier.fillMaxSize()) {
            for (i in 1..2) { drawLine(Color.White.copy(.4f), Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f); drawLine(Color.White.copy(.4f), Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f) }
        }
        focus?.let { Box(Modifier.offset { IntOffset(it.x.toInt() - 36.dp.roundToPx(), it.y.toInt() - 36.dp.roundToPx()) }.size(72.dp).border(2.dp, Color.White, CircleShape)) }
        if (count > 0) Text("$count", Modifier.align(Alignment.Center), color = Color.White, style = MaterialTheme.typography.displayLarge)
        val sh by animateFloatAsState(if (shutter) .85f else 0f, tween(100), label = "sh")
        Box(Modifier.fillMaxSize().background(Color.White.copy(sh)))
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClose) { Icon(Icons.Rounded.Close, "Chiudi") }
            Spacer(Modifier.weight(1f))
            if (rec != null) AssistChip({}, { Text("%d:%02d".format(secs / 60, secs % 60)) })
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton({ if (rec == null) front = !front }) { Icon(Icons.Rounded.FlipCameraAndroid, "Gira") }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasFlash) FilledTonalIconToggleButton(flash, { flash = it }) { Icon(if (flash) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Flash") }
            FilledTonalIconToggleButton(timer, { timer = it }) { Icon(Icons.Rounded.Timer, "Timer 3s") }
            FilledTonalIconToggleButton(grid, { grid = it }) { Icon(Icons.Rounded.GridOn, "Griglia") }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (zs != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(zs.minZoomRatio.takeIf { it < .95f }, 1f, 2f.takeIf { zs.maxZoomRatio >= 2f }).filterNotNull().forEach { z ->
                    FilterChip(kotlin.math.abs(zoom - z) < .1f, { cam?.cameraControl?.setZoomRatio(z) }, { Text("${(z * 10).roundToInt() / 10f}x") })
                }
                if (listOf(1f, 2f).none { kotlin.math.abs(zoom - it) < .1f } && zoom > .5f) Text("%.1fx".format(zoom), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            if (showExp && es?.isExposureCompensationSupported == true) {
                val r = es.exposureCompensationRange
                Slider(exp, { exp = it; cam?.cameraControl?.setExposureCompensationIndex(it.roundToInt()); expTick++ }, Modifier.padding(horizontal = 48.dp), valueRange = r.lower.toFloat()..r.upper.toFloat())
            }
            if (rec == null) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (photo) items(exts) { m -> FilterChip(ext == m, { ext = m }, { Text(extName(m)) }) }
                else items(cam?.let { QualitySelector.getSupportedQualities(it.cameraInfo) }?.filter { it == Quality.UHD || it == Quality.FHD || it == Quality.HD } ?: emptyList()) { q ->
                    FilterChip(quality == q, { quality = q }, { Text(qName(q)) })
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters.size) { i -> FilterChip(f == i, { f = i }, { Text(filters[i].name) }) }
            }
            if (rec == null) SingleChoiceSegmentedButtonRow {
                listOf("Video", "Foto").forEachIndexed { i, s -> SegmentedButton(photo == (i == 1), { photo = i == 1 }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(s) } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton({ gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, Modifier.size(52.dp)) { Icon(Icons.Rounded.PhotoLibrary, "Galleria") }
                val size by animateDpAsState(if (rec != null) 36.dp else 64.dp, spring(.5f, 400f), label = "s")
                val corner by animateDpAsState(if (rec != null) 10.dp else 32.dp, spring(.5f, 400f), label = "c")
                Box(Modifier.size(84.dp).border(4.dp, Color.White, CircleShape), Alignment.Center) {
                    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(if (photo) Color.White else MaterialTheme.colorScheme.error).clickable {
                        if (timer && rec == null && count == 0) scope.launch { for (i in 3 downTo 1) { count = i; delay(1000) }; count = 0; doCapture() } else doCapture()
                    })
                }
                Spacer(Modifier.size(52.dp))
            }
        }
    }
}
