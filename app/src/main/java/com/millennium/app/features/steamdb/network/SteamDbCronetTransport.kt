package com.millennium.app.features.steamdb.network

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One native engine and callback executor for all features in the Steam process. */
internal class SteamDbCronetTransport(private val emit: (Int, String) -> Unit) {
    private val callbacks = Executors.newSingleThreadExecutor()
    private var engine: CronetEngine? = null
    private var unavailable = false

    fun get(context: Context, endpoint: String, headers: Map<String, String>): SteamDbHttpResponse? {
        val activeEngine = getEngine(context) ?: return null
        return try {
            request(activeEngine, endpoint, headers)
        } catch (error: IOException) {
            emit(Log.WARN, "steamdb network cronet request failed endpoint=$endpoint fallback=http-url-connection error=$error")
            null
        }
    }

    @Synchronized
    private fun getEngine(context: Context): CronetEngine? {
        engine?.let { return it }
        if (unavailable) return null
        return runCatching {
            val moduleLoader = checkNotNull(CronetEngine::class.java.classLoader)
            val wrapped = CronetContext(context.applicationContext, moduleLoader)
            val providers = CronetProvider.getAllProviders(wrapped)
            emit(
                Log.INFO,
                "steamdb network cronet providers count=${providers.size} " +
                        "hostLoader=${context.classLoader.javaClass.name} moduleLoader=${moduleLoader.javaClass.name} " +
                        "providers=${providers.joinToString { "${it.name}:${it.isEnabled}" }}",
            )
            val provider = providers.firstOrNull {
                it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED && it.isEnabled
            } ?: throw IOException("Bundled Cronet provider unavailable in module class loader")
            emit(Log.INFO, "steamdb network cronet provider selected name=${provider.name} version=${provider.version}")
            provider.createBuilder()
                .setUserAgent(SteamDbHttpClient.USER_AGENT)
                .enableHttp2(true).enableQuic(true).enableBrotli(true).build()
                .also {
                    engine = it
                    emit(Log.INFO, "steamdb network cronet initialized version=${it.versionString} engine=${it.javaClass.name}")
                }
        }.onFailure {
            unavailable = true
            emit(Log.WARN, "steamdb network cronet initialization failed fallback=http-url-connection error=${Log.getStackTraceString(it)}")
        }.getOrNull()
    }

    private fun request(
        engine: CronetEngine,
        endpoint: String,
        headers: Map<String, String>,
    ): SteamDbHttpResponse {
        val completed = CountDownLatch(1)
        // Written on the callback thread; CountDownLatch publishes the completed response.
        var failure: IOException? = null
        var responseInfo: UrlResponseInfo? = null
        val body = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocateDirect(16 * 1024)
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                request.read(buffer)
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                // Decode once at the end: a UTF-8 character can straddle two callbacks.
                body.write(bytes)
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                responseInfo = info
                completed.countDown()
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                failure = IOException("Cronet ${error.message}", error)
                completed.countDown()
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                failure = IOException("Cronet request cancelled")
                completed.countDown()
            }
        }
        val builder = engine.newUrlRequestBuilder(endpoint, callback, callbacks).setHttpMethod("GET")
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val request = builder.build()
        request.start()
        try {
            if (!completed.await(SteamDbHttpClient.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)) {
                request.cancel()
                throw IOException("Cronet request timeout")
            }
        } catch (error: InterruptedException) {
            request.cancel()
            Thread.currentThread().interrupt()
            throw error
        }
        failure?.let { throw it }
        val info = responseInfo ?: throw IOException("Cronet response had no status")
        return SteamDbHttpResponse(info.httpStatusCode, body.toString("UTF-8"), info.allHeaders, "cronet")
    }

    // Steam's ClassLoader cannot discover providers bundled inside the Xposed module.
    private class CronetContext(context: Context, private val moduleLoader: ClassLoader) : ContextWrapper(context) {
        override fun getClassLoader(): ClassLoader = moduleLoader
        override fun getApplicationContext(): Context = this
    }
}
