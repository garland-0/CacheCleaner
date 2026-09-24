package com.example.cachecleaner

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.resume

/**
 * Talks to the hidden IPackageManager APIs through Shizuku (shell uid).
 *
 * Callback notes (learned the hard way):
 * - Observers MUST be held with a strong reference until their callback fires,
 *   otherwise GC collects the proxy and the reply silently never arrives.
 * - Continuations MUST be cancellable (suspendCancellableCoroutine), otherwise
 *   timeouts can't break a wait whose callback never comes.
 */
object ShizukuCache {

    /** Strong refs to live observer proxies, so they survive GC until called back. */
    private val pendingObservers = Collections.synchronizedSet(mutableSetOf<Any>())

    /** Returns a binder proxy for android.content.pm.IPackageManager. */
    fun iPm(): Any {
        val smClass = Class.forName("android.os.ServiceManager")
        val binder = smClass.getMethod("getService", String::class.java)
            .invoke(null, "package") as IBinder
        val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
        return stubClass.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
    }

    suspend fun clearCache(iPm: Any, packageName: String): Boolean =
        doClear(iPm, packageName, false, 0)

    suspend fun clearCacheAsUser(iPm: Any, packageName: String, userId: Int): Boolean =
        doClear(iPm, packageName, true, userId)

    private suspend fun doClear(iPm: Any, packageName: String, asUser: Boolean, userId: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            var invoked = false
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
            val box = arrayOfNulls<Any>(1)
            val handler = InvocationHandler { _, method, args ->
                if (method.name == "onRemoveCompleted" && !invoked) {
                    invoked = true
                    box[0]?.let { pendingObservers.remove(it) }
                    if (cont.isActive) cont.resume((args?.get(1) as? Boolean) ?: false)
                }
                null
            }
            val observer = newAidlProxy(observerClass, handler)
            box[0] = observer
            pendingObservers.add(observer)
            cont.invokeOnCancellation { pendingObservers.remove(observer) }
            try {
                val m = if (asUser) {
                    findMethod(
                        iPm.javaClass, "deleteApplicationCacheFilesAsUser",
                        String::class.java, Int::class.javaPrimitiveType!!, observerClass
                    )
                } else {
                    findMethod(
                        iPm.javaClass, "deleteApplicationCacheFiles",
                        String::class.java, observerClass
                    )
                }
                if (asUser) m.invoke(iPm, packageName, userId, observer)
                else m.invoke(iPm, packageName, observer)
            } catch (t: Throwable) {
                pendingObservers.remove(observer)
                if (!invoked) {
                    invoked = true
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    suspend fun queryCacheSize(iPm: Any, packageName: String): Long =
        doQuerySize(iPm, packageName, false, 0)

    suspend fun queryCacheSizeAsUser(iPm: Any, packageName: String, userId: Int): Long =
        doQuerySize(iPm, packageName, true, userId)

    private suspend fun doQuerySize(iPm: Any, packageName: String, asUser: Boolean, userId: Int): Long =
        suspendCancellableCoroutine { cont ->
            var invoked = false
            val observerClass = Class.forName("android.content.pm.IPackageStatsObserver")
            val statsClass = Class.forName("android.content.pm.PackageStats")
            val box = arrayOfNulls<Any>(1)
            val handler = InvocationHandler { _, method, args ->
                if (method.name == "onGetStatsCompleted" && !invoked) {
                    invoked = true
                    box[0]?.let { pendingObservers.remove(it) }
                    val stats = args?.get(0)
                    val ok = (args?.get(1) as? Boolean) ?: false
                    val result = if (!ok || stats == null) -1L else {
                        var total = 0L
                        for (f in arrayOf("cacheSize", "externalCacheSize")) {
                            try {
                                total += statsClass.getField(f).getLong(stats)
                            } catch (_: Throwable) {
                            }
                        }
                        total
                    }
                    if (cont.isActive) cont.resume(result)
                }
                null
            }
            val observer = newAidlProxy(observerClass, handler)
            box[0] = observer
            pendingObservers.add(observer)
            cont.invokeOnCancellation { pendingObservers.remove(observer) }
            try {
                val m = if (asUser) {
                    findMethod(
                        iPm.javaClass, "getPackageSizeInfo",
                        String::class.java, Int::class.javaPrimitiveType!!, observerClass
                    )
                } else {
                    findMethod(
                        iPm.javaClass, "getPackageSizeInfo",
                        String::class.java, observerClass
                    )
                }
                if (asUser) m.invoke(iPm, packageName, userId, observer)
                else m.invoke(iPm, packageName, observer)
            } catch (t: Throwable) {
                pendingObservers.remove(observer)
                if (!invoked) {
                    invoked = true
                    if (cont.isActive) cont.resume(-1L)
                }
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
     * Builds a dynamic proxy for a hidden single-method AIDL interface whose
     * binder callbacks really get delivered: the proxy answers asBinder() with
     * a local Binder whose onTransact forwards to [handler].
     */
    private fun newAidlProxy(iface: Class<*>, handler: InvocationHandler): Any {
        val localBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                return try {
                    data.enforceInterface(iface.name)
                    when (code) {
                        IBinder.FIRST_CALL_TRANSACTION -> {
                            val m = iface.declaredMethods.firstOrNull { it.name.startsWith("on") }
                            handler.invoke(null, m, readArgs(m, data))
                            reply?.writeNoException()
                            true
                        }
                        else -> super.onTransact(code, data, reply, flags)
                    }
                } catch (t: Throwable) {
                    false
                }
            }
        }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> localBinder
                else -> handler.invoke(proxy, method, args)
            }
        }
    }

    /** Reads AIDL args for the single "on..." method of these observer interfaces. */
    private fun readArgs(method: Method?, data: Parcel): Array<out Any?>? {
        if (method == null) return null
        val params = method.parameterTypes
        val args = arrayOfNulls<Any>(params.size)
        for (i in params.indices) {
            args[i] = when (params[i]) {
                String::class.java -> data.readString()
                Boolean::class.javaPrimitiveType -> data.readInt() != 0
                Int::class.javaPrimitiveType -> data.readInt()
                else -> {
                    val present = data.readInt()
                    if (present == 0) {
                        null
                    } else {
                        val creator = runCatching {
                            params[i].getField("CREATOR").get(null) as Parcelable.Creator<*>
                        }.getOrNull()
                        val start = data.dataPosition()
                        val maybeName = data.readString()
                        if (maybeName != params[i].name) data.setDataPosition(start)
                        creator?.createFromParcel(data)
                    }
                }
            }
        }
        return args
    }
}
