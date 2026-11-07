package com.example.rootguide

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BATTERY_MIN_PERCENT = 80
private const val MAGISK_PACKAGE = "com.topjohnwu.magisk"

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
        Button(onClick = onAccept, enabled = checked, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.btn_continue))
        }
    }
}

@Composable
private fun GuideScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fb = remember { FastbootController(ctx) }
    val adb = remember { AdbController(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { fb.disconnect() } }

    val batteryLevel = remember { batteryPercent(ctx) }
    var fbInfo by remember { mutableStateOf<FastbootInfo?>(null) }
    var adbModel by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var openStep by rememberSaveable { mutableStateOf(1) }
    var doneSteps by remember { mutableStateOf(setOf<Int>()) }
    var backup by remember { mutableStateOf<ByteArray?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCheck by remember { mutableStateOf<FlashCheck?>(null) }

    // הקולבקים של הבקר רצים ברקע, לכן כל עדכון מחזיר לתור הראשי דרך scope
    val setLog: (String) -> Unit = { msg -> scope.launch { log = msg } }
    fun markDone(step: Int) { doneSteps = doneSteps + step }

    fun connectFastboot() {
        scope.launch {
            busy = true
            status = ctx.getString(R.string.status_connecting)
            fb.connect()
                .onSuccess { fbInfo = it; status = null }
                .onFailure { fbInfo = null; status = it.message }
            busy = false
        }
    }

    fun connectAdb() {
        scope.launch {
            busy = true
            adb.connect()
                .onSuccess { adbModel = it.model; status = null }
                .onFailure { adbModel = null; setLog(it.message ?: "") }
            busy = false
        }
    }

    fun rebootToFastboot() {
        scope.launch {
            busy = true
            adb.reboot(RebootTarget.FASTBOOT)
                .onSuccess {
                    adbModel = null
                    setLog(ctx.getString(R.string.adb_to_fastboot_wait))
                    delay(4000)
                    connectFastboot()
                }
                .onFailure { setLog(it.message ?: "") }
            busy = false
        }
    }

    fun disconnectFastboot() {
        fb.disconnect()
        fbInfo = null
        status = null
    }

    fun disableVerity() {
        scope.launch {
            busy = true
            fb.fetchPartition("vbmeta", SlotChoice.AUTO, {}, setLog)
                .onSuccess { bytes ->
                    val patched = disableAvbFlags(bytes)
                    if (patched == null) {
                        setLog(ctx.getString(R.string.err_vbmeta_not_avb))
                    } else {
                        fb.flashBytes("vbmeta", SlotChoice.AUTO, patched, {}, setLog)
                            .onSuccess { setLog(ctx.getString(R.string.verity_done)); markDone(2) }
                            .onFailure { setLog(it.message ?: "") }
                    }
                }
                .onFailure { err ->
                    // השאיבה נכשלה: מנסים את פקודת ה-OEM כגיבוי
                    setLog(ctx.getString(R.string.err_fetch_failed, err.message ?: ""))
                    fb.runCommand("oem disable-verification", setLog)
                        .onSuccess { setLog(it) }
                        .onFailure { setLog(it.message ?: "") }
                }
            busy = false
        }
    }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val data = backup
        if (uri != null && data != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } }
                .onSuccess { setLog(ctx.getString(R.string.backup_saved)); markDone(3) }
                .onFailure { setLog(ctx.getString(R.string.err_file_open)) }
        }
    }

    fun fetchBoot() {
        scope.launch {
            busy = true
            fb.fetchPartition("boot", SlotChoice.AUTO, {}, setLog)
                .onSuccess {
                    backup = it
                    setLog(ctx.getString(R.string.fetch_boot_ok))
                    saveBackup.launch("boot_backup.img")
                }
                .onFailure { setLog(ctx.getString(R.string.err_fetch_failed, it.message ?: "")) }
            busy = false
        }
    }

    fun openMagisk() {
        val intent = ctx.packageManager.getLaunchIntentForPackage(MAGISK_PACKAGE)
        if (intent == null) setLog(ctx.getString(R.string.magisk_missing)) else ctx.startActivity(intent)
    }

    val pickPatched = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                fb.checkFlash("boot", SlotChoice.AUTO, uri)
                    .onSuccess { check ->
                        val size = check.partitionSize
                        if (size != null && check.fileSize > size) {
                            setLog(ctx.getString(R.string.err_flash_too_big))
                        } else {
                            pendingUri = uri
                            pendingCheck = check
                        }
                    }
                    .onFailure { setLog(it.message ?: "") }
            }
        }
    }

    fun rebootSystem() {
        scope.launch {
            busy = true
            fb.reboot()
            fbInfo = null
            setLog(ctx.getString(R.string.rebooting_system))
            markDone(6)
            busy = false
        }
    }

    // דיאלוג אישור לפני צריבת boot
    val check = pendingCheck
    val flashUri = pendingUri
    if (check != null && flashUri != null) {
        AlertDialog(
            onDismissRequest = { pendingCheck = null; pendingUri = null },
            title = { Text(stringResource(R.string.flash_boot_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.flash_check_body,
                        check.target,
                        fmtMb(check.partitionSize),
                        flashUri.lastPathSegment ?: "boot.img",
                        fmtMb(check.fileSize)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCheck = null
                    pendingUri = null
                    scope.launch {
                        busy = true
                        fb.flash("boot", SlotChoice.AUTO, flashUri, {}, setLog)
                            .onSuccess { setLog(ctx.getString(R.string.flash_boot_done)); markDone(5) }
                            .onFailure { setLog(it.message ?: "") }
                        busy = false
                    }
                }) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCheck = null; pendingUri = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
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

        // כרטיס חיבור: Fastboot ו-ADB
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = fbInfo
                if (current == null) {
                    Text(text = status ?: stringResource(R.string.status_disconnected))
                    Button(onClick = { connectFastboot() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.btn_connect))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.status_connected, current.product),
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = stringResource(R.string.bootloader_label, current.unlocked))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { disconnectFastboot() }, enabled = !busy) {
                            Text(text = stringResource(R.string.btn_disconnect))
                        }
                        OutlinedButton(onClick = { rebootSystem() }, enabled = !busy) {
                            Text(text = stringResource(R.string.btn_reboot))
                        }
                    }
                }

                val model = adbModel
                if (model == null) {
                    OutlinedButton(onClick = { connectAdb() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.btn_adb_connect))
                    }
                } else {
                    Text(text = stringResource(R.string.adb_connected, model))
                    Button(
                        onClick = { rebootToFastboot() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_to_fastboot))
                    }
                }
            }
        }

        val fbReady = fbInfo != null

        StepCard(
            index = 1,
            title = stringResource(R.string.step1_title),
            expanded = openStep == 1,
            done = fbInfo?.unlocked == "yes" || 1 in doneSteps,
            onToggle = { openStep = if (openStep == 1) 0 else 1 }
        ) {
            Text(text = stringResource(R.string.step1_body))
            Text(text = stringResource(R.string.step1_how))
        }

        StepCard(
            index = 2,
            title = stringResource(R.string.step2_title),
            expanded = openStep == 2,
            done = 2 in doneSteps,
            onToggle = { openStep = if (openStep == 2) 0 else 2 }
        ) {
            Text(text = stringResource(R.string.step2_body))
            Button(onClick = { disableVerity() }, enabled = !busy && fbReady, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.btn_disable_verity))
            }
        }

        StepCard(
            index = 3,
            title = stringResource(R.string.step3_title),
            expanded = openStep == 3,
            done = 3 in doneSteps,
            onToggle = { openStep = if (openStep == 3) 0 else 3 }
        ) {
            Text(text = stringResource(R.string.step3_body))
            Button(onClick = { fetchBoot() }, enabled = !busy && fbReady, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.btn_fetch_boot))
            }
        }

        StepCard(
            index = 4,
            title = stringResource(R.string.step4_title),
            expanded = openStep == 4,
            done = 4 in doneSteps,
            onToggle = { openStep = if (openStep == 4) 0 else 4 }
        ) {
            Text(text = stringResource(R.string.step4_body))
            OutlinedButton(onClick = { openMagisk() }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.btn_open_magisk))
            }
            Button(onClick = { markDone(4) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.btn_patch_done))
            }
        }

        StepCard(
            index = 5,
            title = stringResource(R.string.step5_title),
            expanded = openStep == 5,
            done = 5 in doneSteps,
            onToggle = { openStep = if (openStep == 5) 0 else 5 }
        ) {
            Text(text = stringResource(R.string.step5_body))
            Button(
                onClick = { pickPatched.launch(arrayOf("*/*")) },
                enabled = !busy && fbReady,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.btn_pick_patched))
            }
        }

        StepCard(
            index = 6,
            title = stringResource(R.string.step6_title),
            expanded = openStep == 6,
            done = 6 in doneSteps,
            onToggle = { openStep = if (openStep == 6) 0 else 6 }
        ) {
            Text(text = stringResource(R.string.step6_body))
        }

        if (log.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = stringResource(R.string.log_label), fontWeight = FontWeight.Bold)
                    Text(text = log)
                }
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
