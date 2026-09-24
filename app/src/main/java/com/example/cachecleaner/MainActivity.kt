package com.example.cachecleaner

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        const val REQ_SHIZUKU = 1001
    }

    internal var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                onPermissionResult?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
                onPermissionResult = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                HomeScreen()
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    fun requestShizukuPermission(onResult: (Boolean) -> Unit) {
        val already = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (already) {
            onResult(true)
            return
        }
        onPermissionResult = onResult
        runCatching { Shizuku.requestPermission(REQ_SHIZUKU) }
            .onFailure {
                onPermissionResult = null
                onResult(false)
            }
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val pm = context.packageManager
    val statsManager = remember {
        context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
    }
    val appOps = remember {
        context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    }

    fun usageGranted() =
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED

    fun checkShizukuPerm() =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    var shizukuAlive by remember { mutableStateOf(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) }
    var granted by remember { mutableStateOf(false) }
    var statsGranted by remember { mutableStateOf(usageGranted()) }
    var loading by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf("") }
    var currentLabel by remember { mutableStateOf("") }
    var currentIcon by remember { mutableStateOf<ImageBitmap?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var freedBytes by remember { mutableLongStateOf(0L) }
    var report by remember { mutableStateOf("") }
    var selectAll by remember { mutableStateOf(true) }
    val apps = remember { mutableStateListOf<AppEntry>() }
    val results = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    val totalSelected = apps.filter { it.selected }.sumOf { it.cacheBytes.coerceAtLeast(0) }

    suspend fun querySize(pkg: String): Long = withContext(Dispatchers.IO) {
        if (usageGranted()) {
            runCatching {
                statsManager.queryStatsForPackage(
                    android.os.storage.StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()
                ).cacheBytes
            }.getOrDefault(-1L)
        } else {
            -1L
        }
    }

    suspend fun refresh() {
        if (loading) return
        loading = true
        try {
            val list = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { app ->
                    val label = runCatching { app.loadLabel(pm).toString() }
                        .getOrDefault(app.packageName)
                    val size = querySize(app.packageName)
                    if (size == 0L) {
                        null
                    } else {
                        val icon = runCatching {
                            drawableToBitmap(pm.getApplicationIcon(app.packageName)).asImageBitmap()
                        }.getOrNull()
                        AppEntry(app.packageName, label, size, icon, selected = selectAll)
                    }
                }.sortedByDescending { it.cacheBytes }
            }
            apps.clear()
            apps.addAll(list)
        } finally {
            loading = false
        }
    }

    suspend fun clearSelected() {
        val targets = apps.filter { it.selected }
        if (targets.isEmpty() || clearing) return
        clearing = true
        freedBytes = 0L
        progress = 0f
        results.clear()
        report = ""
        val userId = Process.myUserHandle().hashCode()
        val notes = mutableListOf<String>()
        var okCount = 0
        var method = ""
        try {
            val iPm = withContext(Dispatchers.IO) { runCatching { ShizukuCache.iPm() }.getOrNull() }

            suspend fun runMethod(m: String, pkg: String): ApiResult {
                return if (m == "API") {
                    if (iPm != null) {
                        ShizukuCache.clearCacheApi(iPm, pkg, userId)
                    } else {
                        ApiResult(false, "IPackageManager unavailable")
                    }
                } else {
                    ShizukuCache.clearCacheShell(pkg, userId)
                }
            }

            suspend fun account(entry: AppEntry, before: Long, ok: Boolean) {
                results[entry.packageName] = ok
                if (!ok) return
                okCount++
                val after = querySize(entry.packageName)
                freedBytes += when {
                    before <= 0L -> 0L
                    after in 0L..before -> before - after
                    after < 0L -> before
                    else -> 0L
                }
            }

            // Step 1: on the biggest cache, find a per-app method that really works.
            val first = targets.first()
            currentLabel = first.label
            currentIcon = first.icon
            statusLine = "TESTING CLEAR METHOD"
            val firstBefore = if (first.cacheBytes > 0L) first.cacheBytes else querySize(first.packageName)
            for (m in listOf("API", "SHELL")) {
                val r = runMethod(m, first.packageName)
                var worked = r.ok
                var note = r.note
                if (worked && firstBefore > 0L) {
                    var after = querySize(first.packageName)
                    if (after >= firstBefore) {
                        delay(500) // usage stats can lag a little behind the delete
                        after = querySize(first.packageName)
                    }
                    if (after >= firstBefore) {
                        worked = false
                        note = "reported ok but cache size did not change"
                    }
                }
                notes += "$m: " + (if (worked) "ok" else note)
                if (worked) {
                    method = m
                    break
                }
            }

            // Step 2: run the working method on every selected app, or trim everything.
            var trimOk = false
            if (method.isNotEmpty()) {
                account(first, firstBefore, true)
                for ((i, entry) in targets.withIndex()) {
                    if (entry.packageName == first.packageName) continue
                    currentLabel = entry.label
                    currentIcon = entry.icon
                    statusLine = "CLEARING ${i + 1} OF ${targets.size}"
                    progress = i / targets.size.toFloat()
                    val before = if (entry.cacheBytes > 0L) entry.cacheBytes else querySize(entry.packageName)
                    val r = runMethod(method, entry.packageName)
                    if (!r.ok && notes.size < 5) notes += entry.label + ": " + r.note
                    account(entry, before, r.ok)
                }
            } else {
                method = "TRIM"
                statusLine = "PER-APP BLOCKED - TRIMMING ALL"
                currentLabel = "All apps"
                currentIcon = null
                progress = 0.5f
                var r = ShizukuCache.trimAllShell()
                notes += "TRIM shell: " + (if (r.ok) "ok" else r.note)
                if (!r.ok && iPm != null) {
                    r = ShizukuCache.trimAllApi(iPm)
                    notes += "TRIM api: " + (if (r.ok) "ok" else r.note)
                }
                trimOk = r.ok
            }

            progress = 1f
            refresh()

            if (method == "TRIM") {
                // Judge each app by its size after the trim.
                for (t in targets) {
                    val after = apps.firstOrNull { it.packageName == t.packageName }?.cacheBytes ?: 0L
                    val ok = if (t.cacheBytes > 0L) after in 0L until t.cacheBytes else trimOk
                    results[t.packageName] = ok
                    if (ok) {
                        okCount++
                        freedBytes += (t.cacheBytes - after).coerceAtLeast(0L)
                    }
                }
            }

            val methodLabel = when (method) {
                "API" -> "DIRECT API"
                "SHELL" -> "SHELL COMMAND"
                "TRIM" -> "TRIM ALL CACHES"
                else -> "NONE"
            }
            statusLine = "DONE"
            report = buildString {
                append("CLEARED ").append(okCount).append(" OF ").append(targets.size)
                append("  |  FREED ").append(formatBytes(freedBytes))
                append("\nMETHOD: ").append(methodLabel)
                for (n in notes) append("\n").append(n)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            report = "ERROR: " + (t.message ?: t.javaClass.simpleName)
        } finally {
            clearing = false
            currentIcon = null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shizukuAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                if (shizukuAlive && !granted) granted = checkShizukuPerm()
                statsGranted = usageGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(shizukuAlive) {
        if (shizukuAlive) granted = checkShizukuPerm()
    }
    LaunchedEffect(shizukuAlive, granted) {
        if (shizukuAlive && granted && apps.isEmpty() && !loading) refresh()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(
            "CACHE CLEANER",
            fontFamily = ZenDots,
            fontSize = 19.sp,
            color = TextP,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))

        when {
            !shizukuAlive -> {
                Spacer(Modifier.height(30.dp))
                NeuCard(Modifier.fillMaxWidth(), corner = 28.dp, pad = 22.dp) {
                    Column {
                        Text("SHIZUKU IS NOT RUNNING", fontFamily = ZenDots, fontSize = 12.sp, color = TextS)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Start Shizuku, then check again.",
                            color = TextP,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                shizukuAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("CHECK AGAIN", color = Color.White) }
                    }
                }
            }

            !granted -> {
                Spacer(Modifier.height(30.dp))
                NeuCard(Modifier.fillMaxWidth(), corner = 28.dp, pad = 22.dp) {
                    Column {
                        Text("PERMISSION NEEDED", fontFamily = ZenDots, fontSize = 12.sp, color = TextS)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Grant access via Shizuku so caches of other apps can be cleared.",
                            color = TextP,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { activity.requestShizukuPermission { granted = it } },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("GRANT VIA SHIZUKU", color = Color.White) }
                    }
                }
            }

            else -> {
                // Hero circle
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(200.dp).offset(7.dp, 9.dp).shadow(20.dp, CircleShape, clip = false, ambientColor = DarkSh, spotColor = DarkSh))
                        Box(Modifier.size(200.dp).offset((-7).dp, (-9).dp).shadow(20.dp, CircleShape, clip = false, ambientColor = LightSh, spotColor = LightSh))
                        Box(
                            Modifier.size(200.dp).clip(CircleShape).background(SurfaceC),
                            contentAlignment = Alignment.Center
                        ) {
                            val icon = currentIcon
                            if (clearing && icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(140.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        formatBytes(totalSelected),
                                        fontFamily = ZenDots,
                                        fontSize = 26.sp,
                                        color = TextP
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (loading) "LOADING" else "SELECTED",
                                        fontFamily = ZenDots,
                                        fontSize = 10.sp,
                                        color = TextS
                                    )
                                }
                            }
                        }
                    }
                }

                // Controls
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NeuCircleButton(
                        onClick = { scope.launch { refresh() } },
                        enabled = !loading && !clearing
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = TextP)
                    }
                    Spacer(Modifier.width(30.dp))
                    NeuCircleButton(
                        onClick = { scope.launch { clearSelected() } },
                        size = 86.dp,
                        accent = true,
                        enabled = !loading && !clearing && apps.any { it.selected }
                    ) {
                        Icon(Icons.Default.CleaningServices, null, tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    Spacer(Modifier.width(30.dp))
                    NeuCircleButton(
                        onClick = {
                            selectAll = !selectAll
                            apps.forEachIndexed { i, a -> apps[i] = a.copy(selected = selectAll) }
                        },
                        enabled = !clearing
                    ) {
                        Icon(
                            if (selectAll) Icons.Default.Deselect else Icons.Default.SelectAll,
                            null,
                            tint = TextP
                        )
                    }
                }

                if (clearing) {
                    Spacer(Modifier.height(14.dp))
                    NeuCard(Modifier.fillMaxWidth(), corner = 22.dp, pad = 16.dp) {
                        Column {
                            Text(statusLine, fontFamily = ZenDots, fontSize = 11.sp, color = TextS)
                            Spacer(Modifier.height(4.dp))
                            Text(currentLabel, color = TextP, fontSize = 15.sp, maxLines = 1)
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Accent,
                                trackColor = TrackC
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "FREED " + formatBytes(freedBytes),
                                fontFamily = ZenDots,
                                fontSize = 11.sp,
                                color = Accent
                            )
                        }
                    }
                }

                if (!clearing && report.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    NeuCard(Modifier.fillMaxWidth(), corner = 18.dp, pad = 12.dp) {
                        Text(report, color = TextS, fontSize = 11.sp, maxLines = 10)
                    }
                }

                if (!statsGranted) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Enable usage access to show cache sizes",
                            color = TextS,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        NeuCircleButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                statsGranted = usageGranted()
                            },
                            size = 44.dp
                        ) {
                            Icon(Icons.Default.Settings, null, tint = TextP, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(apps, key = { _, a -> a.packageName }) { index, entry ->
                        NeuCard(Modifier.fillMaxWidth().clickable(enabled = !clearing) {
                            apps[index] = entry.copy(selected = !entry.selected)
                        }, corner = 22.dp, pad = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(entry)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.label, color = TextP, fontSize = 14.sp, maxLines = 1)
                                    Text(entry.packageName, color = TextS, fontSize = 10.sp, maxLines = 1)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        formatBytes(entry.cacheBytes),
                                        fontFamily = ZenDots,
                                        fontSize = 12.sp,
                                        color = TextP
                                    )
                                    results[entry.packageName]?.let { ok ->
                                        Text(
                                            if (ok) "CLEARED" else "FAILED",
                                            fontFamily = ZenDots,
                                            fontSize = 9.sp,
                                            color = if (ok) Accent else Color(0xFFFF5252)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(if (entry.selected) Accent else Color(0xFF45454F))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
