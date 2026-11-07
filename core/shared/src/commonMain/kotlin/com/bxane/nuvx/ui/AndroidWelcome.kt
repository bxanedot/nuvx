package com.bxane.nuvx.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class AndroidWelcomeStep(val title: String, val detail: String)

private val AndroidWelcomeSteps = listOf(
    AndroidWelcomeStep("Your next favourite.", "Search for songs, artists and albums, then start listening as a guest."),
    AndroidWelcomeStep("Find the right mood.", "Explore music, discover new tracks and keep the songs you like in your library."),
    AndroidWelcomeStep("Make listening yours.", "Build a queue, choose your look and keep music playing in the background."),
)

@Composable
internal fun AndroidWelcome(onDone: () -> Unit, reduceMotion: Boolean) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = remember { AndroidWelcomeSteps }
    Column(
        Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                "Skip",
                Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onDone)
                    .semantics { contentDescription = "Skip introduction" }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = NuvxColors.Secondary,
                fontSize = 15.sp,
            )
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (reduceMotion) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                else (slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 480f)) { -it / 4 } + fadeOut(tween(140)))
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "androidOnboardingPage",
        ) { index ->
            val page = steps[index]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NuvxBrandMark("nuvx", Modifier.size(86.dp))
                Spacer(Modifier.height(34.dp))
                Text(page.title, color = NuvxColors.Text, fontSize = 34.sp, lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(page.detail, color = NuvxColors.Secondary, fontSize = 17.sp, lineHeight = 25.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp, start = 12.dp, end = 12.dp))
            }
        }
        Row(Modifier.padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            steps.indices.forEach { index ->
                Box(
                    Modifier.size(width = if (index == step) 22.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape).background(if (index == step) NuvxColors.Accent else NuvxColors.Secondary.copy(alpha = 0.35f))
                        .semantics { selected = index == step; contentDescription = "Introduction page ${index + 1} of ${steps.size}" },
                )
            }
        }
        Button(
            onClick = { if (step == steps.lastIndex) onDone() else step++ },
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = NuvxColors.Accent, contentColor = NuvxColors.OnAccent),
        ) {
            Text(if (step == steps.lastIndex) "Start listening" else "Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
