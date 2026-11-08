@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package com.example.toktok

import android.os.Build
import android.net.Uri
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

// ---------- ONBOARDING ----------
@Composable
fun Onboarding(done: () -> Unit) {
    val pages = listOf(
        Triple(MaterialShapes.Cookie9Sided, "Crea video", "Registra con la fotocamera, con filtri e zoom."),
        Triple(MaterialShapes.Sunny, "Montaggio facile", "Taglia, aggiungi testo, sticker e filtri."),
        Triple(MaterialShapes.Heart, "Il tuo profilo", "Collega TikTok e invia i video alle bozze."),
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

