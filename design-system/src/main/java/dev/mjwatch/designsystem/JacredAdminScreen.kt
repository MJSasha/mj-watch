package dev.mjwatch.designsystem

import android.net.Uri
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mjwatch.core.SearchAdminAddress
import dev.mjwatch.core.safeError
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException

@Composable
internal fun SearchAdminScreen(
    tv: Boolean,
    service: String,
    onClose: () -> Unit,
    connect: suspend ((SearchAdminAddress) -> Unit) -> Unit,
) {
    var address by remember { mutableStateOf<SearchAdminAddress?>(null) }
    var error by remember { mutableStateOf("") }
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        val wasSecure = window?.attributes?.flags?.and(secureFlag) == secureFlag
        val inputMode = window?.attributes?.softInputMode
        window?.addFlags(secureFlag)
        if (inputMode != null) {
            window.setSoftInputMode(
                (inputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        onDispose {
            if (!wasSecure) window?.clearFlags(secureFlag)
            if (inputMode != null) window.setSoftInputMode(inputMode)
        }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = !imeVisible, onBack = onClose)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, onEvent = onClose)
    if (error.isBlank()) LaunchedEffect(Unit) {
        try {
            connect { address = it }
            error = "SSH-сеанс завершён. Откройте админку заново."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = safeError(failure)
        } finally {
            address = null
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Админка $service", style = MaterialTheme.typography.titleLarge)
                Action("Закрыть", tv, secondary = true, onClick = onClose)
            }
            val connection = address
            if (connection != null && error.isBlank()) {
                SearchAdminWebView(connection, service, Modifier.weight(1f)) { error = it }
            } else if (error.isNotBlank()) {
                Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Открываем SSH-туннель…", Modifier.padding(16.dp))
            }
        }
    }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SearchAdminWebView(address: SearchAdminAddress, service: String, modifier: Modifier, onError: (String) -> Unit) {
    val cookies = remember { CookieManager.getInstance() }
    fun belongsToTunnel(uri: Uri): Boolean =
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == address.port && uri.userInfo == null

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                tag = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                cookies.setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !belongsToTunnel(request.url)

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (belongsToTunnel(request.url)) null else WebResourceResponse(
                            "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()),
                        )

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) onError("Не удалось открыть веб-админку $service.")
                    }

                    override fun onReceivedHttpError(
                        view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) onError("Веб-админка $service ответила HTTP ${errorResponse.statusCode}.")
                    }
                }
                val browser = this
                if (address.cookie == null) {
                    loadUrl(address.origin)
                } else cookies.setCookie(
                    address.origin, "${address.cookie}=${address.token}; Path=/; HttpOnly; SameSite=Strict",
                ) { stored ->
                    if (browser.tag == true) {
                        if (stored) browser.loadUrl("${address.origin}/settings")
                        else onError("Устройство не сохранило сеанс админки $service.")
                    }
                }
            }
        },
        onRelease = { browser ->
            browser.tag = false
            val names = address.cookie?.let(::listOf) ?: cookies.getCookie(address.origin).orEmpty()
                .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
            names.forEach { cookies.setCookie(address.origin, "$it=; Path=/; Max-Age=0") }
            if (address.cookie == null) android.webkit.WebStorage.getInstance().deleteOrigin(address.origin)
            browser.stopLoading()
            browser.destroy()
        },
    )
}
