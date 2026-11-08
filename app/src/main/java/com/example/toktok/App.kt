@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
package com.example.toktok

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ---------- API UFFICIALI TIKTOK (Display API + Content Posting API) ----------
object Api {
    private const val H = "https://open.tiktokapis.com/v2"
    private fun JSONObject.ok() = optJSONObject("error")?.optString("code") == "ok"
    private fun JSONObject.msg() = optJSONObject("error")?.optString("message") ?: "errore"
    private fun call(url: String, token: String, post: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        if (post != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); c.outputStream.use { it.write(post.toByteArray()) } }
        val s = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        return JSONObject(s)
    }
    fun user(t: String): JSONObject {
        var r = call("$H/user/info/?fields=open_id,avatar_url,display_name,bio_description,follower_count,following_count,likes_count,video_count", t)
        if (!r.ok()) r = call("$H/user/info/?fields=open_id,avatar_url,display_name", t)
        if (!r.ok()) throw Exception(r.msg())
        return r.getJSONObject("data").getJSONObject("user")
    }
    fun videos(t: String): JSONArray {
        val r = call("$H/video/list/?fields=id,title,cover_image_url,share_url,duration", t, """{"max_count":20}""")
        if (!r.ok()) throw Exception(r.msg())
        return r.getJSONObject("data").optJSONArray("videos") ?: JSONArray()
    }
    // Carica il video nelle bozze di TikTok (scope video.upload)
    fun upload(t: String, f: File) {
        val n = f.length()
        val r = call("$H/post/publish/inbox/video/init/", t, """{"source_info":{"source":"FILE_UPLOAD","video_size":$n,"chunk_size":$n,"total_chunk_count":1}}""")
        if (!r.ok()) throw Exception(r.msg())
        val c = URL(r.getJSONObject("data").getString("upload_url")).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.setFixedLengthStreamingMode(n)
        c.setRequestProperty("Content-Type", "video/mp4"); c.setRequestProperty("Content-Range", "bytes 0-${n - 1}/$n")
        f.inputStream().use { i -> c.outputStream.use { o -> i.copyTo(o) } }
        if (c.responseCode !in 200..299) throw Exception("upload HTTP ${c.responseCode}")
    }
}

fun prefs(ctx: Context) = ctx.getSharedPreferences("tt", Context.MODE_PRIVATE)
fun creations(ctx: Context): List<File> = ctx.getExternalFilesDir(null)?.listFiles { f -> f.name.startsWith("v_") || f.name.startsWith("p_") }?.sortedByDescending { it.lastModified() } ?: emptyList()
fun saveToGallery(ctx: Context, f: File): Boolean = runCatching {
    val v = f.name.startsWith("v_")
    val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, f.name); put(MediaStore.MediaColumns.MIME_TYPE, if (v) "video/mp4" else "image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH, if (v) "Movies/TokTok" else "Pictures/TokTok") }
    val u = ctx.contentResolver.insert(if (v) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
    ctx.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
}.isSuccess

@Composable
fun NetImage(url: String?, mod: Modifier) {
    val b by produceState<ImageBitmap?>(null, url) { value = if (url == null) null else withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeStream(URL(url).openStream()).asImageBitmap() }.getOrNull() } }
    b?.let { Image(it, null, mod, contentScale = ContentScale.Crop) } ?: Box(mod.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

// ---------- ROOT ----------
@Composable
fun Root(dark: Boolean, onDark: (Boolean) -> Unit) {
    var onboarded by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    if (!onboarded) { Onboarding { onboarded = true }; return }
    BackHandler(camera || edit != null) { if (edit != null) edit = null else camera = false }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") {
            if (it == 0) Home(rev, { camera = true }) { u, p -> edit = u to p } else Profile(dark, onDark)
        }
        val items = listOf(Icons.Rounded.Home to "Studio", Icons.Rounded.Person to "Profilo")
        AnimatedVisibility(!camera && edit == null, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it * 2 }, exit = slideOutVertically { it * 2 }) {
            Row(Modifier.navigationBarsPadding().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, shadowElevation = 10.dp) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items.forEachIndexed { i, (ic, lb) ->
                            val sel = tab == i
                            val bg by animateColorAsState(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "bg")
                            Row(Modifier.clip(CircleShape).background(bg).clickable { tab = i }.padding(horizontal = 18.dp, vertical = 12.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(ic, lb, tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (sel) Text(lb, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                FloatingActionButton({ camera = true }, shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Rounded.Add, "Crea") }
            }
        }
        AnimatedVisibility(camera, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            CameraScreen({ camera = false }) { u, p -> edit = u to p; camera = false }
        }
        AnimatedVisibility(edit != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            edit?.let { (u, p) -> EditorScreen(u, p, { edit = null }) { _, _ -> edit = null; tab = 0; rev++ } }
        }
    }
}

// ---------- STUDIO ----------
@Composable
fun Home(rev: Int, onCreate: () -> Unit, onPick: (Uri, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { onPick(it, ctx.contentResolver.getType(it)?.startsWith("image") == true) } }
    var local by remember { mutableIntStateOf(0) }
    val files = remember(rev, local) { creations(ctx) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 130.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Studio", style = MaterialTheme.typography.displaySmallEmphasized) }
        item {
            Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Crea un nuovo video", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text("Registra con filtri, zoom e timer, poi taglia e aggiungi testo e sticker.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onCreate) { Icon(Icons.Rounded.Videocam, null); Spacer(Modifier.width(8.dp)); Text("Registra") }
                        FilledTonalButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) { Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Galleria") }
                    }
                }
            }
        }
        item { Text("Le tue creazioni", style = MaterialTheme.typography.titleMedium) }
        if (files.isEmpty()) item { Text("Ancora niente: premi + per iniziare.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(files, key = { it.name }) { CreationCard(it) { local++ } }
    }
}

@Composable
fun CreationCard(f: File, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = f.name.startsWith("v_")
    var busy by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (video) MediaMetadataRetriever().run { setDataSource(f.path); val b = getFrameAtTime(0); release(); b }
            else BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }.getOrNull()?.asImageBitmap() }
    }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(80.dp, 112.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), Alignment.Center) {
                thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (video) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (video) "Video" else "Foto", style = MaterialTheme.typography.titleMedium)
                Text(SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(f.lastModified())), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ toast(if (saveToGallery(ctx, f)) "Salvato in galleria" else "Errore nel salvataggio") }) { Icon(Icons.Rounded.Download, "Salva") }
                    if (video) FilledTonalButton({
                        val t = prefs(ctx).getString("token", "") ?: ""
                        if (t.isBlank()) toast("Collega TikTok dal Profilo") else {
                            busy = true
                            scope.launch { val r = runCatching { withContext(Dispatchers.IO) { Api.upload(t, f) } }; busy = false
                                toast(if (r.isSuccess) "Inviato alle bozze di TikTok" else "Errore: ${r.exceptionOrNull()?.message}") }
                        }
                    }, enabled = !busy) { if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text("TikTok") }
                    IconButton({ f.delete(); onChange() }) { Icon(Icons.Rounded.Delete, "Elimina") }
                }
            }
        }
    }
}

// ---------- PROFILO ----------
@Composable
fun Profile(dark: Boolean, onDark: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val p = remember { prefs(ctx) }
    var ver by remember { mutableIntStateOf(0) }
    var token by remember { mutableStateOf(p.getString("token", "") ?: "") }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var vids by remember { mutableStateOf(JSONArray()) }
    var err by remember { mutableStateOf<String?>(null) }
    var editSheet by remember { mutableStateOf(false) }
    var tokenDlg by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        err = null; info = null; vids = JSONArray()
        if (token.isNotBlank()) runCatching { withContext(Dispatchers.IO) { Api.user(token) } }.onSuccess { info = it }.onFailure { err = it.message }
        if (token.isNotBlank() && err == null) runCatching { withContext(Dispatchers.IO) { Api.videos(token) } }.onSuccess { vids = it }.onFailure { err = "Video: ${it.message}" }
    }
    val name = info?.optString("display_name")?.takeIf { it.isNotBlank() } ?: p.getString("name", "tuo_nome")!!
    val bio = info?.optString("bio_description")?.takeIf { it.isNotBlank() } ?: p.getString("bio", "")!!
    val avatarFile = File(ctx.filesDir, "avatar.jpg")
    val local = remember(ver) { if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.path)?.asImageBitmap() else null }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 130.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val shape = MaterialShapes.Cookie9Sided.toShape()
                val avatarMod = Modifier.size(104.dp).clip(shape)
                when {
                    info?.optString("avatar_url").orEmpty().isNotBlank() -> NetImage(info!!.optString("avatar_url"), avatarMod)
                    local != null -> Image(local, null, avatarMod, contentScale = ContentScale.Crop)
                    else -> Box(avatarMod.background(MaterialTheme.colorScheme.tertiaryContainer), Alignment.Center) { Text("😎", fontSize = 48.sp) }
                }
                Text("@$name", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
                if (bio.isNotBlank()) Text(bio, color = MaterialTheme.colorScheme.onSurfaceVariant)
                info?.let { i ->
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        listOf("following_count" to "Seguiti", "follower_count" to "Follower", "likes_count" to "Mi piace").forEach { (k, l) ->
                            if (i.has(k)) Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${i.optLong(k)}", style = MaterialTheme.typography.titleLarge); Text(l, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ editSheet = true }) { Icon(Icons.Rounded.Edit, null); Spacer(Modifier.width(8.dp)); Text("Modifica profilo") }
                    FilledTonalButton({ if (token.isBlank()) tokenDlg = true else { p.edit().remove("token").apply(); token = "" } }) { Text(if (token.isBlank()) "Collega TikTok" else "Scollega") }
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Surface(Modifier.padding(vertical = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    ListItem({ Text("Tema scuro") }, leadingContent = { Icon(Icons.Rounded.DarkMode, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = { Switch(dark, onDark) })
                }
                if (vids.length() > 0) Text("I tuoi video su TikTok", Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        items(vids.length()) { i ->
            val v = vids.getJSONObject(i)
            NetImage(v.optString("cover_image_url"), Modifier.aspectRatio(.7f).clip(RoundedCornerShape(16.dp)).clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.getString("share_url")))) } })
        }
    }
    if (tokenDlg) {
        var t by remember { mutableStateOf("") }
        AlertDialog({ tokenDlg = false }, title = { Text("Collega TikTok") },
            text = { Column { Text("Incolla l'access token OAuth (scope: user.info.basic, user.info.profile, user.info.stats, video.list, video.upload).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp)); OutlinedTextField(t, { t = it }, singleLine = true, label = { Text("Access token") }) } },
            confirmButton = { TextButton({ if (t.isNotBlank()) { p.edit().putString("token", t.trim()).apply(); token = t.trim() }; tokenDlg = false }) { Text("Collega") } },
            dismissButton = { TextButton({ tokenDlg = false }) { Text("Annulla") } })
    }
    if (editSheet) {
        var n by remember { mutableStateOf(p.getString("name", "tuo_nome")!!) }
        var b by remember { mutableStateOf(p.getString("bio", "")!!) }
        val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
            u?.let { ctx.contentResolver.openInputStream(it)?.use { i -> avatarFile.outputStream().use { o -> i.copyTo(o) } }; ver++ } }
        ModalBottomSheet({ editSheet = false }) {
            Column(Modifier.padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Modifica profilo", style = MaterialTheme.typography.titleLarge)
                Box(Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.tertiaryContainer).clickable { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Alignment.Center) {
                    if (local != null) Image(local, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Rounded.AddAPhoto, "Foto")
                }
                OutlinedTextField(n, { n = it }, Modifier.fillMaxWidth(), label = { Text("Nome utente") }, singleLine = true)
                OutlinedTextField(b, { b = it }, Modifier.fillMaxWidth(), label = { Text("Bio") }, maxLines = 3)
                Text("Le modifiche restano nell'app: l'API di TikTok non permette di cambiare il profilo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ p.edit().putString("name", n.trim()).putString("bio", b.trim()).apply(); ver++; editSheet = false }, Modifier.fillMaxWidth()) { Text("Salva") }
            }
        }
    }
}
