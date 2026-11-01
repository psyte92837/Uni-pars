package com.unipars.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unipars.app.R
import com.unipars.app.UniParsApp
import com.unipars.app.ui.theme.gradientBrush

private val steps = listOf(
    R.string.ob_welcome_title,
    R.string.ob_ai_title,
    R.string.ob_theme_title,
    R.string.ob_notify_title,
    R.string.ob_background_title,
    R.string.ob_done_title,
)

/** First-run setup: AI provider & key, look, notifications (permission asked right away), background. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val app = LocalContext.current.applicationContext as UniParsApp
    var step by rememberSaveable { mutableIntStateOf(0) }
    val finish = {
        app.prefs.onboardingDone = true
        onFinish()
    }

    Box(Modifier.fillMaxSize().background(gradientBrush())) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (step < steps.lastIndex) TextButton(onClick = finish) { Text(stringResource(R.string.ob_skip), color = Color.White) }
            }
            StepDots(step, steps.size)
            Text(
                stringResource(steps[step]),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            ElevatedCard(
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
                    },
                    label = "onboarding",
                ) { s ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (s) {
                            0 -> Welcome()
                            1 -> { Text(stringResource(R.string.ob_ai_text)); AiSetupSection() }
                            2 -> { Text(stringResource(R.string.ob_theme_text)); AppearanceSection() }
                            3 -> { Text(stringResource(R.string.ob_notify_text)); NotificationsSection(requestImmediately = true) }
                            4 -> { Text(stringResource(R.string.ob_background_text)); BackgroundSection() }
                            else -> Text(stringResource(R.string.ob_done_text))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) {
                    TextButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_back), color = Color.White) }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Button(
                    onClick = { if (step < steps.lastIndex) step++ else finish() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (step < steps.lastIndex) R.string.ob_next else R.string.ob_start)) }
            }
        }
    }
}

@Composable
private fun Welcome() {
    listOf(R.string.ob_welcome_1, R.string.ob_welcome_2, R.string.ob_welcome_3, R.string.ob_welcome_4).forEachIndexed { i, id ->
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(gradientBrush()), contentAlignment = Alignment.Center) {
                Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(id), modifier = Modifier.padding(start = 12.dp, top = 3.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .size(width = if (i == current) 24.dp else 8.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i <= current) 1f else 0.4f)),
            )
        }
    }
}
