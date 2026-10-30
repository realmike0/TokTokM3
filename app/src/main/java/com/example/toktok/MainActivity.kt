@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package com.example.toktok

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var dark by remember { mutableStateOf<Boolean?>(null) }
            val isDark = dark ?: androidx.compose.foundation.isSystemInDarkTheme()
            TokTokTheme(isDark) { Root(isDark) { dark = it } }
        }
    }
}

@Composable
fun TokTokTheme(dark: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val cs = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(colorScheme = cs, motionScheme = MotionScheme.expressive(), content = content)
}

data class Chat(val name: String, val emoji: String, val last: String)
data class Msg(val id: Int, val text: String, val mine: Boolean, val kind: Int = 0) // 0 testo, 1 sticker, 2 foto

val chats = listOf(
    Chat("luna", "🌙", "Hai visto il video?"), Chat("marco", "🎸", "Sticker inviato"),
    Chat("giulia", "🌸", "Ci sei stasera?"), Chat("dario", "🚀", "Foto"), Chat("sara", "🍋", "Ahah top"),
)
val grads = listOf(
    listOf(Color(0xFF6750A4), Color(0xFFEF5DA8)), listOf(Color(0xFF00695C), Color(0xFF80CBC4)),
    listOf(Color(0xFFE65100), Color(0xFFFFCC80)), listOf(Color(0xFF1A237E), Color(0xFF42A5F5)),
)

@Composable
fun Root(dark: Boolean, onDark: (Boolean) -> Unit) {
    var onboarded by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chat by remember { mutableStateOf<Chat?>(null) }
    var story by remember { mutableStateOf<Chat?>(null) }
    if (!onboarded) { Onboarding { onboarded = true }; return }
    BackHandler(chat != null || story != null) { if (story != null) story = null else chat = null }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AnimatedContent(tab, transitionSpec = { fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) togetherWith fadeOut() }) {
            when (it) {
                0 -> Feed()
                1 -> SearchScreen()
                2 -> Inbox({ c -> chat = c }, { c -> story = c })
                else -> Profile(dark, onDark)
            }
        }
        // Nav bar fluttuante
        val items = listOf(Icons.Rounded.Home to "Home", Icons.Rounded.Search to "Cerca", Icons.Rounded.ChatBubble to "Inbox", Icons.Rounded.Person to "Profilo")
        AnimatedVisibility(chat == null && story == null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it * 2 }, exit = slideOutVertically { it * 2 }) {
            Surface(Modifier.navigationBarsPadding().padding(bottom = 12.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, shadowElevation = 10.dp) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.forEachIndexed { i, (ic, lb) ->
                        val sel = tab == i
                        val bg by animateColorAsState(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        Row(Modifier.clip(CircleShape).background(bg).clickable { tab = i }
                            .padding(horizontal = 18.dp, vertical = 12.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(ic, lb, tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (sel) Text(lb, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(chat != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            chat?.let { ChatScreen(it) { chat = null } }
        }
        AnimatedVisibility(story != null, enter = scaleIn(initialScale = .6f) + fadeIn(), exit = scaleOut(targetScale = .6f) + fadeOut()) {
            story?.let { StoryViewer(it) { story = null } }
        }
    }
}

// ---------- ONBOARDING ----------
@Composable
fun Onboarding(done: () -> Unit) {
    val pages = listOf(
        Triple(MaterialShapes.Cookie9Sided, "Scorri, scopri", "Video infiniti, uno swipe alla volta."),
        Triple(MaterialShapes.Sunny, "Chatta e condividi", "Messaggi, sticker e foto con animazioni fluide."),
        Triple(MaterialShapes.Heart, "Fallo tuo", "Colori dinamici che seguono il tuo sfondo."),
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val rot by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)))
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(pager, Modifier.weight(1f)) { p ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(260.dp).rotate(rot).clip(pages[p].first.toShape()).background(MaterialTheme.colorScheme.primaryContainer))
                Spacer(Modifier.height(40.dp))
                Text(pages[p].second, style = MaterialTheme.typography.displaySmallEmphasized, fontWeight = FontWeight.Bold)
                Text(pages[p].third, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
            repeat(pages.size) {
                val w by animateDpAsState(if (pager.currentPage == it) 28.dp else 10.dp, spring(.6f, 400f))
                Box(Modifier.height(10.dp).width(w).clip(CircleShape).background(
                    if (pager.currentPage == it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Button({ if (pager.currentPage == pages.lastIndex) done() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (pager.currentPage == pages.lastIndex) "Inizia" else "Avanti", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------- FEED VERTICALE ----------
@Composable
fun Feed() {
    val pager = rememberPagerState { 12 }
    var forYou by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        VerticalPager(pager, Modifier.fillMaxSize()) { Video(it) }
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            SingleChoiceSegmentedButtonRow {
                listOf("Seguiti", "Per te").forEachIndexed { i, s ->
                    SegmentedButton(forYou == (i == 1), { forYou = i == 1 }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(s) }
                }
            }
        }
    }
}

@Composable
fun Video(i: Int) {
    var liked by remember { mutableStateOf(false) }
    var burst by remember { mutableIntStateOf(0) }
    val g = grads[i % grads.size]
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(g))
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { liked = true; burst++ }) }) {
        // Placeholder: qui va il player Media3 (PlayerView in AndroidView)
        val big by animateFloatAsState(if (burst > 0) 1f else 0f, spring(.35f, 300f), label = "burst")
        LaunchedEffect(burst) { if (burst > 0) { delay(600); burst = 0 } }
        Icon(Icons.Rounded.Favorite, null, Modifier.align(Alignment.Center).size(120.dp).scale(big).alpha(big), tint = MaterialTheme.colorScheme.primaryContainer)
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val s by animateFloatAsState(if (liked) 1.2f else 1f, spring(Spring.DampingRatioHighBouncy, 400f), label = "heart")
            FilledTonalIconToggleButton(liked, { liked = it }, Modifier.size(56.dp).scale(s)) {
                Icon(Icons.Rounded.Favorite, "Mi piace", tint = if (liked) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            Text(if (liked) "12,4K" else "12,3K", color = Color.White, style = MaterialTheme.typography.labelMedium)
            FilledTonalIconButton({}, Modifier.size(56.dp)) { Icon(Icons.Rounded.ChatBubble, "Commenti") }
            FilledTonalIconButton({}, Modifier.size(56.dp)) { Icon(Icons.Rounded.Share, "Condividi") }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 110.dp, end = 90.dp)) {
            Text("@creator$i", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Video #$i con Material 3 Expressive ✨ #m3e", color = Color.White.copy(.9f))
        }
    }
}

// ---------- AVATAR + STORIE ----------
@Composable
fun Avatar(c: Chat, size: Dp = 56.dp, ring: Boolean = false, onClick: () -> Unit = {}) {
    Box(Modifier.size(size + 8.dp).clickable(onClick = onClick), Alignment.Center) {
        if (ring) Box(Modifier.fillMaxSize().clip(MaterialShapes.Cookie9Sided.toShape()).background(Brush.sweepGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary))))
        Box(Modifier.size(size).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.tertiaryContainer), Alignment.Center) {
            Text(c.emoji, fontSize = (size.value / 2).sp)
        }
    }
}

@Composable
fun StoriesRow(onStory: (Chat) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(chats) { c ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(c, 64.dp, ring = true) { onStory(c) }
                Text(c.name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun StoryViewer(c: Chat, onClose: () -> Unit) {
    var idx by remember { mutableIntStateOf(0) }
    val p = remember(idx) { Animatable(0f) }
    LaunchedEffect(idx) { p.animateTo(1f, tween(4000, easing = LinearEasing)); if (idx < 2) idx++ else onClose() }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(grads[(idx + c.name.length) % grads.size]))
        .pointerInput(idx) { detectTapGestures { if (it.x > size.width / 2 && idx < 2) idx++ else if (it.x <= size.width / 2 && idx > 0) idx-- else onClose() } }) {
        Column(Modifier.statusBarsPadding().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { LinearProgressIndicator({ if (it < idx) 1f else if (it == idx) p.value else 0f }, Modifier.weight(1f).height(4.dp), drawStopIndicator = {}) }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(c, 36.dp); Text(c.name, Modifier.weight(1f).padding(start = 8.dp), color = Color.White, style = MaterialTheme.typography.titleMedium)
                IconButton(onClose) { Icon(Icons.Rounded.Close, "Chiudi", tint = Color.White) }
            }
        }
        Text(c.emoji, Modifier.align(Alignment.Center), fontSize = 140.sp)
    }
}

// ---------- INBOX / CHAT ----------
@Composable
fun Inbox(onChat: (Chat) -> Unit, onStory: (Chat) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Text("Inbox", Modifier.padding(16.dp), style = MaterialTheme.typography.displaySmallEmphasized) }
        item { StoriesRow(onStory); Spacer(Modifier.height(12.dp)) }
        items(chats) { c ->
            ListItem({ Text(c.name, fontWeight = FontWeight.SemiBold) }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(MaterialTheme.shapes.extraLarge).clickable { onChat(c) },
                supportingContent = { Text(c.last, maxLines = 1) }, leadingContent = { Avatar(c) },
                trailingContent = { Badge { Text("2") } })
        }
    }
}

@Composable
fun Bubble(m: Msg) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 2 } +
            scaleIn(spring(Spring.DampingRatioMediumBouncy, 400f), transformOrigin = TransformOrigin(if (m.mine) 1f else 0f, 1f))) {
            when (m.kind) {
                1 -> Text(m.text, fontSize = 72.sp)
                2 -> Box(Modifier.size(200.dp, 240.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(grads[m.id % grads.size])), Alignment.Center) {
                    Icon(Icons.Rounded.Image, null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
                else -> Surface(color = if (m.mine) cs.primaryContainer else cs.surfaceContainerHighest,
                    shape = if (m.mine) RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp)) {
                    Text(m.text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
fun ChatScreen(c: Chat, onBack: () -> Unit) {
    val msgs = remember { mutableStateListOf(Msg(0, "Ciao! 👋", false)) }
    var text by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var tray by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(msgs.size, typing) { state.animateScrollToItem(msgs.size) }
    fun send(m: Msg) {
        msgs.add(m)
        scope.launch {
            delay(500); typing = true; delay(1400); typing = false
            val r = listOf("Ahah 😂", "Top!", "Mandami altro", "🔥").random()
            msgs.add(if (Random.nextInt(3) == 0) Msg(msgs.size, "🥳", false, 1) else Msg(msgs.size, r, false))
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().imePadding()) {
        TopAppBar({ Row(verticalAlignment = Alignment.CenterVertically) { Avatar(c, 36.dp); Text(c.name, Modifier.padding(start = 8.dp)) } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Rounded.Close, "Indietro") } })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = state) {
            items(msgs, key = { it.id.toString() + it.text }) { Bubble(it) }
            item { AnimatedVisibility(typing, enter = scaleIn(), exit = fadeOut()) { LoadingIndicator(Modifier.size(40.dp)) } }
        }
        AnimatedVisibility(tray) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("😍", "🔥", "😂", "💜", "🚀").forEach { e ->
                    Surface({ send(Msg(msgs.size, e, true, 1)); tray = false }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(e, Modifier.padding(12.dp), fontSize = 28.sp)
                    }
                }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ tray = !tray }) { Icon(Icons.Rounded.EmojiEmotions, "Sticker") }
            IconButton({ send(Msg(msgs.size, "", true, 2)) }) { Icon(Icons.Rounded.Image, "Foto") } // TODO: Photo Picker
            TextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Messaggio") }, shape = CircleShape,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            Spacer(Modifier.width(8.dp))
            FilledIconButton({ if (text.isNotBlank()) { send(Msg(msgs.size, text.trim(), true)); text = "" } }, Modifier.size(52.dp)) { Icon(Icons.Rounded.Send, "Invia") }
        }
    }
}

// ---------- CERCA ----------
@Composable
fun SearchScreen() {
    var q by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf(false) }
    val tags = listOf("danza", "cucina", "viaggi", "musica", "gaming", "fitness", "arte", "comedy")
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SearchBar(inputField = {
            SearchBarDefaults.InputField(q, { q = it }, { exp = false }, exp, { exp = it }, placeholder = { Text("Cerca video, utenti, suoni") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) })
        }, expanded = exp, onExpandedChange = { exp = it }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            LazyColumn { items(tags.filter { it.contains(q, true) }) { ListItem({ Text(it) }, leadingContent = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.clickable { q = it; exp = false }) } }
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tags.size) { i ->
                Box(Modifier.fillMaxWidth().height(if (i % 3 == 0) 220.dp else 170.dp).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(grads[i % grads.size])).padding(16.dp), Alignment.BottomStart) {
                    Text("#${tags[i]}", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

// ---------- PROFILO + IMPOSTAZIONI ----------
@Composable
fun Profile(dark: Boolean, onDark: (Boolean) -> Unit) {
    var notif by remember { mutableStateOf(true) }
    var priv by remember { mutableStateOf(false) }
    @Composable fun Group(content: @Composable ColumnScope.() -> Unit) =
        Surface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Column(content = content) }
    @Composable fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, t: String, v: Boolean, on: (Boolean) -> Unit) =
        ListItem({ Text(t) }, leadingContent = { Icon(icon, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = { Switch(v, on, thumbContent = if (v) { { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) } } else null) })
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Spacer(Modifier.height(24.dp)); Avatar(Chat("tu", "😎", ""), 96.dp, ring = true)
            Text("@tuo_nome", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf("128" to "Seguiti", "4,2K" to "Follower", "98K" to "Mi piace").forEach { (n, l) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(n, style = MaterialTheme.typography.titleLarge); Text(l, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        item { Group { Toggle(Icons.Rounded.DarkMode, "Tema scuro", dark, onDark); Toggle(Icons.Rounded.Notifications, "Notifiche", notif) { notif = it } } }
        item { Group { Toggle(Icons.Rounded.Lock, "Account privato", priv) { priv = it }
            ListItem({ Text("Lingua") }, leadingContent = { Icon(Icons.Rounded.Language, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            ListItem({ Text("Informazioni") }, leadingContent = { Icon(Icons.Rounded.Info, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
    }
}
