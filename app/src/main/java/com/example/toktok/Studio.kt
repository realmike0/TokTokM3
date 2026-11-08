@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, UnstableApi::class)
package com.example.toktok

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.ColorMatrix as ACM
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint as APaint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.RgbAdjustment
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

// ---------- FILTRI (stessa matrice per anteprima/foto, effetti Media3 per l'export video) ----------
class Filt(val name: String, val cm: ACM, val fx: List<Effect>)
val filters = listOf(
    Filt("Normale", ACM(), emptyList()),
    Filt("Vivido", ACM().apply { setSaturation(1.5f) }, listOf(HslAdjustment.Builder().adjustSaturation(40f).build())),
    Filt("B/N", ACM().apply { setSaturation(0f) }, listOf(HslAdjustment.Builder().adjustSaturation(-100f).build())),
    Filt("Caldo", ACM(floatArrayOf(1.15f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, .85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        listOf(RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(.85f).build())),
    Filt("Freddo", ACM(floatArrayOf(.85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        listOf(RgbAdjustment.Builder().setRedScale(.85f).setBlueScale(1.15f).build())),
)

class Ov(val text: String, val sticker: Boolean) { var x by mutableFloatStateOf(.3f); var y by mutableFloatStateOf(.4f) }

fun drawOv(c: ACanvas, w: Int, h: Int, ovs: List<Ov>, viewW: Float, dens: Float) {
    val k = w / viewW
    val p = APaint(APaint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    ovs.forEach { o ->
        p.textSize = (if (o.sticker) 64f else 30f) * dens * k
        p.setShadowLayer(6f * k, 0f, 0f, android.graphics.Color.BLACK)
        c.drawText(o.text, o.x * w, o.y * h + p.textSize * .85f, p)
    }
}

// ---------- EDITOR (video + foto): filtri, testo, sticker, taglio, export ----------
fun decodePhoto(ctx: android.content.Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val m = maxOf(info.size.width, info.size.height); var ss = 1
        while (m / ss > 4096) ss *= 2
        if (ss > 1) d.setTargetSampleSize(ss)
    }

fun mediaDims(ctx: android.content.Context, uri: Uri, photo: Boolean): Pair<Int, Int> {
    if (photo) { val b = decodePhoto(ctx, uri); return b.width to b.height }
    val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
    fun m(k: Int) = r.extractMetadata(k)?.toIntOrNull() ?: 0
    val (w, h, rot) = Triple(m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
    r.release(); return if (rot == 90 || rot == 270) h to w else w to h
}

@Composable
fun EditorScreen(uri: Uri, photo: Boolean, onClose: () -> Unit, onPublish: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val dens = LocalDensity.current.density
    var f by remember { mutableIntStateOf(0) }
    val ovs = remember { mutableStateListOf<Ov>() }
    var caption by remember { mutableStateOf("") }
    var textDlg by remember { mutableStateOf(false) }
    var tray by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize(1, 1)) }
    var trim by remember { mutableStateOf(0f..1f) }
    val (vw, vh) = remember(uri) { mediaDims(ctx, uri, photo) }
    val dur = remember(uri) { if (photo) 0L else MediaMetadataRetriever().run { setDataSource(ctx, uri); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L; release(); d } }
    val bmp = remember(uri) { if (photo) decodePhoto(ctx, uri) else null }
    val player = remember { if (photo) null else ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_ONE; setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(f) { player?.setVideoEffects(filters[f].fx) }

    fun publish() {
        busy = true
        val now = System.currentTimeMillis()
        if (photo) {
            val src = bmp!!
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = ACanvas(out)
            c.drawBitmap(src, 0f, 0f, APaint().apply { colorFilter = ColorMatrixColorFilter(filters[f].cm) })
            drawOv(c, out.width, out.height, ovs, box.width.toFloat(), dens)
            val file = File(ctx.getExternalFilesDir(null), "p_$now.jpg")
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            busy = false; onPublish(Uri.fromFile(file).toString(), caption); return
        }
        val fx = filters[f].fx.toMutableList()
        if (ovs.isNotEmpty()) {
            val bm = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            drawOv(ACanvas(bm), vw, vh, ovs, box.width.toFloat(), dens)
            fx += OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(bm)))
        }
        val file = File(ctx.getExternalFilesDir(null), "v_$now.mp4")
        val mi = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs((trim.start * dur).toLong()).setEndPositionMs((trim.endInclusive * dur).toLong()).build()).build()
        Transformer.Builder(ctx).setEncoderFactory(DefaultEncoderFactory.Builder(ctx).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(if (vw * vh > 2_200_000) 40_000_000 else 16_000_000).build()).setEnableFallback(true).build()).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { busy = false; onPublish(Uri.fromFile(file).toString(), caption) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                busy = false; Toast.makeText(ctx, "Errore export: ${exportException.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        }).build().start(EditedMediaItem.Builder(mi).setEffects(Effects(emptyList(), fx)).build(), file.absolutePath)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.systemBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Rounded.Close, "Chiudi") }
                Spacer(Modifier.weight(1f))
                Button({ publish() }, enabled = !busy) { Icon(Icons.Rounded.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Salva") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Box(Modifier.aspectRatio(vw.toFloat() / vh).clip(RoundedCornerShape(28.dp)).onSizeChanged { box = it }) {
                    if (photo) Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix(filters[f].cm.array)))
                    else AndroidView({ PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; this.player = player } }, Modifier.fillMaxSize())
                    ovs.forEach { o ->
                        Text(o.text, Modifier.offset { IntOffset((o.x * box.width).toInt(), (o.y * box.height).toInt()) }.pointerInput(o) {
                            detectDragGestures { ch, d -> ch.consume(); o.x = (o.x + d.x / box.width).coerceIn(0f, .9f); o.y = (o.y + d.y / box.height).coerceIn(0f, .9f) }
                        }, color = Color.White, fontSize = if (o.sticker) 64.sp else 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ textDlg = true }) { Icon(Icons.Rounded.TextFields, null); Spacer(Modifier.width(6.dp)); Text("Testo") }
                FilledTonalButton({ tray = !tray }) { Icon(Icons.Rounded.EmojiEmotions, null); Spacer(Modifier.width(6.dp)); Text("Sticker") }
                IconButton({ if (ovs.isNotEmpty()) ovs.removeLast() }) { Icon(Icons.Rounded.Delete, "Annulla ultimo") }
            }
            AnimatedVisibility(tray) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("😍", "🔥", "😂", "💜", "🚀", "✨", "🎉", "👑")) { e -> Surface({ ovs.add(Ov(e, true)); tray = false }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Text(e, Modifier.padding(10.dp), fontSize = 28.sp) } }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters.size) { i -> FilterChip(f == i, { f = i }, { Text(filters[i].name) }) }
            }
            if (!photo && dur > 0) Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Taglia", style = MaterialTheme.typography.labelLarge); RangeSlider(trim, { trim = it })
            }
            OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Descrizione") }, shape = RoundedCornerShape(20.dp))
        }
        if (busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(.5f)), Alignment.Center) { LoadingIndicator(Modifier.size(72.dp)) }
    }
    if (textDlg) {
        var t by remember { mutableStateOf("") }
        AlertDialog({ textDlg = false }, confirmButton = { TextButton({ if (t.isNotBlank()) ovs.add(Ov(t.trim(), false)); textDlg = false }) { Text("Aggiungi") } },
            dismissButton = { TextButton({ textDlg = false }) { Text("Annulla") } }, title = { Text("Aggiungi testo") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) })
    }
}
