package com.example.rootguide

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val BATTERY_MIN_PERCENT = 80

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RootGuideTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var accepted by rememberSaveable { mutableStateOf(false) }
                    if (accepted) {
                        GuideScreen()
                    } else {
                        DisclaimerScreen(onAccept = { accepted = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclaimerScreen(onAccept: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.disclaimer_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(text = stringResource(R.string.disclaimer_body))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text(text = stringResource(R.string.disclaimer_check))
        }
        Button(
            onClick = onAccept,
            enabled = checked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.btn_continue))
        }
    }
}

@Composable
private fun GuideScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fb = remember { FastbootController(context) }
    DisposableEffect(Unit) { onDispose { fb.disconnect() } }

    val batteryLevel = remember { batteryPercent(context) }
    var info by remember { mutableStateOf<FastbootInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var openStep by rememberSaveable { mutableStateOf(1) }

    fun connect() {
        scope.launch {
            busy = true
            status = context.getString(R.string.status_connecting)
            fb.connect()
                .onSuccess { info = it; status = null }
                .onFailure { info = null; status = it.message }
            busy = false
        }
    }

    fun disconnect() {
        fb.disconnect()
        info = null
        status = null
    }

    fun reboot() {
        scope.launch {
            busy = true
            fb.reboot()
            info = null
            status = null
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (batteryLevel != null && batteryLevel < BATTERY_MIN_PERCENT) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.battery_level, batteryLevel),
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = stringResource(R.string.battery_warning))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = info
                if (current == null) {
                    Text(text = status ?: stringResource(R.string.status_disconnected))
                    Button(onClick = { connect() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.btn_connect))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.status_connected, current.product),
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { disconnect() }, enabled = !busy) {
                            Text(text = stringResource(R.string.btn_disconnect))
                        }
                        OutlinedButton(onClick = { reboot() }, enabled = !busy) {
                            Text(text = stringResource(R.string.btn_reboot))
                        }
                    }
                }
            }
        }

        StepCard(
            index = 1,
            title = stringResource(R.string.step1_title),
            expanded = openStep == 1,
            done = info?.unlocked == "yes",
            onToggle = { openStep = if (openStep == 1) 0 else 1 }
        ) {
            Text(text = stringResource(R.string.step1_body))
            Text(text = stringResource(R.string.step1_how))
            val unlocked = when (info?.unlocked) {
                "yes" -> stringResource(R.string.bootloader_yes)
                "no" -> stringResource(R.string.bootloader_no)
                null -> stringResource(R.string.bootloader_unknown)
                else -> info?.unlocked ?: stringResource(R.string.bootloader_unknown)
            }
            Text(text = stringResource(R.string.bootloader_label, unlocked), fontWeight = FontWeight.Bold)
        }

        val pending = listOf(
            2 to (R.string.step2_title to R.string.step2_body),
            3 to (R.string.step3_title to R.string.step3_body),
            4 to (R.string.step4_title to R.string.step4_body),
            5 to (R.string.step5_title to R.string.step5_body)
        )
        pending.forEach { (index, res) ->
            StepCard(
                index = index,
                title = stringResource(res.first),
                expanded = openStep == index,
                done = false,
                onToggle = { openStep = if (openStep == index) 0 else index }
            ) {
                Text(text = stringResource(res.second))
                Text(text = stringResource(R.string.coming_soon), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    expanded: Boolean,
    done: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val mark = if (done) " ✓" else ""
                Text(
                    text = "$index. $title$mark",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(text = if (expanded) "▲" else "▼")
            }
            if (expanded) {
                content()
            }
        }
    }
}

private fun batteryPercent(context: Context): Int? {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return level.takeIf { it in 0..100 }
}
