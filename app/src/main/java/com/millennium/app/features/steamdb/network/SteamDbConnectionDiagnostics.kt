package com.millennium.app.features.steamdb.network

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/** Observes the platform calls on a module request's thread without changing their arguments. */
internal object SteamDbConnectionDiagnostics {
    private val hooked = hashSetOf<Method>()

    @Synchronized
    fun install(module: XposedModule, loader: ClassLoader, emit: (Int, String) -> Unit) {
        val counts = mutableMapOf("dns" to 0, "tcp" to 0, "tls" to 0)
        fun register(stage: String, method: Method) {
            if (Modifier.isAbstract(method.modifiers)) return
            runCatching {
                if (method !in hooked) {
                    module.hook(method)
                        .setId("millennium.steamdb.network.$stage.${method.declaringClass.name}.${method.name}")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain -> observe(stage, chain) }
                    hooked.add(method)
                }
                counts[stage] = counts.getValue(stage) + 1
            }.onFailure {
                emit(Log.WARN, "steamdb network diagnostics hook failed stage=$stage " +
                        "method=$method error=${it.javaClass.simpleName}:${it.message}")
            }
        }

        register("dns", InetAddress::class.java.getMethod("getAllByName", String::class.java))
        register("tcp", Socket::class.java.getMethod(
            "connect", SocketAddress::class.java, Int::class.javaPrimitiveType,
        ))
        val socketClasses = listOf(
            "com.android.org.conscrypt.ConscryptFileDescriptorSocket",
            "com.android.org.conscrypt.ConscryptEngineSocket",
            "com.android.org.conscrypt.OpenSSLSocketImpl",
            "org.conscrypt.ConscryptFileDescriptorSocket",
            "org.conscrypt.ConscryptEngineSocket",
            "org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl",
        )
        val handshakes = socketClasses.mapNotNull { name ->
            runCatching { Class.forName(name, false, loader).getMethod("startHandshake") }.getOrNull()
        }.distinct()
        handshakes.forEach { register("tls", it) }
        val complete = counts.values.all { it > 0 }
        emit(
            if (complete) Log.INFO else Log.WARN,
            "steamdb network diagnostics installed scope=module-request-thread " +
                    "dnsHooks=${counts["dns"]} tcpHooks=${counts["tcp"]} tlsHooks=${counts["tls"]} " +
                    "coverage=${if (complete) "installed" else "partial"}",
        )
    }

    private fun observe(stage: String, chain: XposedInterface.Chain): Any? {
        val trace = SteamDbRequestTrace.current ?: return chain.proceed()
        val details = runCatching {
            when (stage) {
                "dns" -> "host=${chain.args.firstOrNull()}"
                "tcp" -> "${endpoint(chain.args.firstOrNull() as? InetSocketAddress)} " +
                        "timeoutMs=${chain.args.getOrNull(1)}"
                else -> "${endpoint((chain.thisObject as? Socket)?.remoteSocketAddress as? InetSocketAddress)} " +
                        "implementation=${chain.thisObject?.javaClass?.name}"
            }
        }.getOrDefault("details=unavailable")
        trace.logSafely(Log.INFO, "stage=$stage event=start $details totalMs=${trace.elapsedMs}")
        val started = SystemClock.elapsedRealtime()
        val result = try {
            chain.proceed()
        } catch (error: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val cause = error.cause
            trace.logSafely(
                Log.WARN,
                "stage=$stage event=failed $details elapsedMs=$elapsed totalMs=${trace.elapsedMs} " +
                        "error=${error.javaClass.name}:${error.message} " +
                        "cause=${cause?.javaClass?.name}:${cause?.message}",
            )
            throw error
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        // Capture timing before formatting logs; never trigger a reverse DNS lookup.
        val extra = runCatching {
            when (stage) {
                "dns" -> "addresses=${(result as? Array<*>)?.filterIsInstance<InetAddress>()
                    ?.joinToString(",") { address(it) }}"
                "tls" -> "handshake=completed"
                else -> "connected=${(chain.thisObject as? Socket)?.isConnected}"
            }
        }.getOrDefault("resultDetails=unavailable")
        trace.logSafely(
            if (elapsed >= 2_000L) Log.WARN else Log.INFO,
            "stage=$stage event=end $details $extra elapsedMs=$elapsed " +
                    "totalMs=${trace.elapsedMs} slow=${elapsed >= 2_000L}",
        )
        return result
    }

    private fun endpoint(endpoint: InetSocketAddress?): String =
        if (endpoint == null) "ip=unknown family=unknown"
        else "${address(endpoint.address)} port=${endpoint.port} unresolved=${endpoint.isUnresolved}"

    private fun address(address: InetAddress?): String {
        val family = when (address) {
            is Inet4Address -> "IPv4"
            is Inet6Address -> "IPv6"
            else -> "unknown"
        }
        return "ip=${address?.hostAddress ?: "unknown"}/family=$family"
    }

    private fun SteamDbRequestTrace.logSafely(priority: Int, details: String) {
        // Diagnostic failures must not replace a platform return value or network exception.
        runCatching { log(priority, "socket phase", details) }
    }
}
