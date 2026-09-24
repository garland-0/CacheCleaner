package com.example.cachecleaner

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Outcome of one clear attempt: [ok] plus a short note that is shown in the UI. */
data class ApiResult(val ok: Boolean, val note: String)

/** Raw result of a shell command run through Shizuku. */
data class ShellResult(val code: Int, val out: String)

/**
 * Clears other apps' caches through Shizuku (shell uid). Three ways, tried by the UI in order:
 *
 *  1. [clearCacheApi]   - hidden IPackageManager.deleteApplicationCacheFiles(AsUser), per app.
 *  2. [clearCacheShell] - `cmd package clear --cache-only`, per app.
 *  3. [trimAllShell] / [trimAllApi] - `pm trim-caches` equivalent, ALL apps at once.
 *
 * Bug fixed here: the observer proxy used to return null for hashCode()/equals(). The very first
 * pendingObservers.add(...) therefore threw a NullPointerException, so the real binder call was
 * never made and every clear silently "failed". The proxy now answers Object methods properly.
 */
object ShizukuCache {

    /** Roughly what `pm trim-caches 999G` asks for: more than the device can ever have free. */
    private const val TRIM_TARGET_BYTES = 999L * 1024L * 1024L * 1024L

    private val PKG_OK = Regex("^[A-Za-z0-9._]+\$")

    /** Strong refs to live observer proxies until their callback fires (identity based). */
    private val pendingObservers: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))

    /** Returns a binder proxy for android.content.pm.IPackageManager. */
    fun iPm(): Any {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java)
            .invoke(null, "package") as IBinder
        val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
        return stubClass.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
    }

    // ------------------------------------------------------------------ 1. direct binder API

    /** Per-app clear via the hidden IPackageManager API. */
    suspend fun clearCacheApi(iPm: Any, pkg: String, userId: Int, timeoutMs: Long = 4000L): ApiResult {
        var last = ApiResult(false, "not tried")
        for (asUser in booleanArrayOf(true, false)) {
            val r = withTimeoutOrNull(timeoutMs) { callClear(iPm, pkg, userId, asUser) }
                ?: ApiResult(false, "no callback within ${timeoutMs / 1000}s")
            if (r.ok) return r
            last = r
            // Only try the other variant when this one does not exist on this Android version.
            if (!r.note.contains("NoSuchMethod")) break
        }
        return last
    }

    private suspend fun callClear(iPm: Any, pkg: String, userId: Int, asUser: Boolean): ApiResult =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val holder = arrayOfNulls<Any>(1)
            fun finish(r: ApiResult) {
                if (done.compareAndSet(false, true)) {
                    holder[0]?.let { pendingObservers.remove(it) }
                    if (cont.isActive) cont.resume(r)
                }
            }
            try {
                val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
                val observer = newObserver(observerClass) { data ->
                    data.readString() // packageName
                    val succeeded = data.readInt() != 0
                    finish(ApiResult(succeeded, if (succeeded) "ok" else "callback said false"))
                }
                holder[0] = observer
                pendingObservers.add(observer)
                cont.invokeOnCancellation { pendingObservers.remove(observer) }
                if (asUser) {
                    findPmMethod(
                        iPm, "deleteApplicationCacheFilesAsUser",
                        String::class.java, Int::class.javaPrimitiveType!!, observerClass
                    ).invoke(iPm, pkg, userId, observer)
                } else {
                    findPmMethod(
                        iPm, "deleteApplicationCacheFiles",
                        String::class.java, observerClass
                    ).invoke(iPm, pkg, observer)
                }
            } catch (t: Throwable) {
                finish(ApiResult(false, t.brief()))
            }
        }

    /** Global trim via IPackageManager.freeStorageAndNotify (what `pm trim-caches` calls). */
    suspend fun trimAllApi(iPm: Any, timeoutMs: Long = 90_000L): ApiResult {
        return withTimeoutOrNull(timeoutMs) { callTrim(iPm) }
            ?: ApiResult(false, "no callback within ${timeoutMs / 1000}s")
    }

    private suspend fun callTrim(iPm: Any): ApiResult =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val holder = arrayOfNulls<Any>(1)
            fun finish(r: ApiResult) {
                if (done.compareAndSet(false, true)) {
                    holder[0]?.let { pendingObservers.remove(it) }
                    if (cont.isActive) cont.resume(r)
                }
            }
            try {
                val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
                val observer = newObserver(observerClass) { _ ->
                    // "succeeded" is normally false here because the huge target can never be
                    // reached, so any callback just means "finished".
                    finish(ApiResult(true, "done"))
                }
                holder[0] = observer
                pendingObservers.add(observer)
                cont.invokeOnCancellation { pendingObservers.remove(observer) }
                findPmMethod(
                    iPm, "freeStorageAndNotify",
                    String::class.java, Long::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, observerClass
                ).invoke(iPm, null, TRIM_TARGET_BYTES, 0, observer)
            } catch (t: Throwable) {
                finish(ApiResult(false, t.brief()))
            }
        }

    // ------------------------------------------------------------------ 2/3. shell commands

    /** Per-app clear: `cmd package clear --cache-only` (same call `pm clear --cache-only` makes). */
    suspend fun clearCacheShell(pkg: String, userId: Int): ApiResult {
        if (!PKG_OK.matches(pkg)) return ApiResult(false, "bad package name")
        val r = shell("cmd package clear --cache-only --user $userId $pkg 2>&1", 8_000L)
        return ApiResult(r.code == 0 && r.out.contains("Success"), oneLine(r.out.ifEmpty { "exit ${r.code}" }))
    }

    /** All apps at once: `cmd package trim-caches 999G` (= `pm trim-caches`). */
    suspend fun trimAllShell(): ApiResult {
        val r = shell("cmd package trim-caches 999G 2>&1", 120_000L)
        return ApiResult(r.code == 0, oneLine(r.out.ifEmpty { "exit ${r.code}" }))
    }

    /**
     * Last-resort helper for the external part of an app's cache (Android/data/<pkg>/cache),
     * the only part the shell user can delete directly. Internal cache needs the system API.
     */
    suspend fun clearExternalCacheShell(pkgs: List<String>): ApiResult {
        val safe = pkgs.filter { PKG_OK.matches(it) }
        if (safe.isEmpty()) return ApiResult(false, "nothing to clean")
        val script = "for p in " + safe.joinToString(" ") +
            "; do d=/sdcard/Android/data/\$p/cache; " +
            "[ -d \"\$d\" ] && rm -rf \"\$d\"/* \"\$d\"/.[!.]*; done; echo DONE"
        val r = shell("($script) 2>&1", 60_000L)
        val extra = r.out.replace("DONE", "").trim()
        return ApiResult(
            r.out.contains("DONE"),
            if (extra.isEmpty()) "ran on ${safe.size} apps" else oneLine(extra).take(70)
        )
    }

    /** Short facts about this phone that explain why a clear method is blocked. */
    suspend fun diagnose(pm: PackageManager): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("ANDROID ").append(Build.VERSION.RELEASE)
            .append(" / SDK ").append(Build.VERSION.SDK_INT)
            .append(" / ").append(Build.MANUFACTURER.uppercase())
        sb.append("\nSHIZUKU UID ").append(runCatching { Shizuku.getUid() }.getOrDefault(-1))
        for (p in listOf("INTERNAL_DELETE_CACHE_FILES", "DELETE_CACHE_FILES", "CLEAR_APP_CACHE")) {
            val res = runCatching { pm.checkPermission("android.permission.$p", "com.android.shell") }
                .getOrDefault(-99)
            val txt = when (res) {
                PackageManager.PERMISSION_GRANTED -> "YES"
                PackageManager.PERMISSION_DENIED -> "NO"
                else -> "?"
            }
            sb.append("\nSHELL ").append(p).append(": ").append(txt)
        }
        val lg = shell("logcat -d -b system 2>&1 | grep -F 'Only system apps can use' | tail -n 1", 10_000L)
        sb.append("\nLOG: ")
            .append(if (lg.out.contains("Only system apps")) "ignore message FOUND" else "ignore message not found")
        sb.toString()
    }

    // Shizuku.newProcess is private in API 13+, so it is reached by reflection.
    private val newProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    /** Runs [command] with `sh -c` as the Shizuku (shell) user. Output is stdout+stderr. */
    suspend fun shell(command: String, timeoutMs: Long = 15_000L): ShellResult =
        withContext(Dispatchers.IO) {
            val method = newProcessMethod
                ?: return@withContext ShellResult(-1, "Shizuku.newProcess not found")
            val process = try {
                method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            } catch (t: Throwable) {
                return@withContext ShellResult(-1, "shell start failed: " + t.brief())
            }
            val out = StringBuffer()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { out.append(it.readText()) }
                } catch (e: Throwable) {
                    // stream closed or process died - the exit code below reports it
                }
            }
            reader.start()
            reader.join(timeoutMs)
            if (reader.isAlive) {
                runCatching { process.destroy() }
                reader.join(1500L)
                return@withContext ShellResult(-2, "timeout after ${timeoutMs / 1000}s " + out.toString().trim())
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            runCatching { process.destroy() }
            ShellResult(code, out.toString().trim())
        }

    // ------------------------------------------------------------------ helpers

    private fun oneLine(s: String): String = s.replace('\n', ' ').replace('\r', ' ').trim().take(180)

    private fun Throwable.brief(): String {
        var t: Throwable = this
        while (t is InvocationTargetException && t.cause != null) {
            t = t.cause!!
        }
        val msg = (t.message ?: "").replace('\n', ' ')
        return (t.javaClass.simpleName + ": " + msg).take(180)
    }

    /** Looks the method up on the hidden interface first, then on the concrete proxy class. */
    private fun findPmMethod(iPm: Any, name: String, vararg params: Class<*>): Method {
        val iface = Class.forName("android.content.pm.IPackageManager")
        return try {
            findMethod(iface, name, *params)
        } catch (e: NoSuchMethodException) {
            findMethod(iPm.javaClass, name, *params)
        }
    }

    private fun findMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method {
        return try {
            clazz.getMethod(name, *params)
        } catch (e: NoSuchMethodException) {
            clazz.getDeclaredMethod(name, *params).apply { isAccessible = true }
        }
    }

    /**
     * Builds an object implementing the hidden one-method AIDL callback [iface] whose binder
     * callbacks really arrive: asBinder() returns a local Binder whose onTransact hands the
     * incoming Parcel to [onCall] (which reads the arguments itself).
     *
     * hashCode/equals/toString MUST be answered here - returning null for hashCode() makes the
     * Proxy throw a NullPointerException as soon as the object is put in a hash-based set.
     */
    private fun newObserver(iface: Class<*>, onCall: (Parcel) -> Unit): Any {
        val localBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) {
                    return super.onTransact(code, data, reply, flags)
                }
                return try {
                    data.enforceInterface(iface.name)
                    onCall(data)
                    reply?.writeNoException()
                    true
                } catch (t: Throwable) {
                    false
                }
            }
        }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> localBinder
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "CacheObserver@" + Integer.toHexString(System.identityHashCode(proxy))
                else -> null
            }
        }
    }
}
