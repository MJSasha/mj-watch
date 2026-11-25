package dev.mjwatch.designsystem

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mjwatch.core.Config
import dev.mjwatch.core.JacredAdminAddress
import dev.mjwatch.core.ManagedApi
import dev.mjwatch.core.OwnerSsh
import dev.mjwatch.core.SecretStore
import dev.mjwatch.core.SshTarget
import dev.mjwatch.core.safeError
import dev.mjwatch.core.scope
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun JacredAdminDialog(
    config: Config,
    secrets: SecretStore,
    ssh: OwnerSsh,
    target: SshTarget,
    tv: Boolean,
    onClose: () -> Unit,
) {
    var address by remember { mutableStateOf<JacredAdminAddress?>(null) }
    var error by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, onEvent = onClose)
    if (error.isBlank()) LaunchedEffect(config.scope, config.managedUrl, target) {
        try {
            withContext(Dispatchers.IO) {
                val me = ManagedApi(config, secrets).authorized("me")
                check(me.optString("role") == "admin" && me.optString("serverId") == config.serverId) {
                    "Админка JacRed доступна владельцу сервера."
                }
                ssh.browseJacred(target, config.serverId) { address = it }
            }
            error = "SSH-сеанс завершён. Откройте админку заново."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = safeError(failure)
        } finally {
            address = null
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Админка JacRed", style = MaterialTheme.typography.titleLarge)
                    Action("Закрыть", tv, secondary = true, onClick = onClose)
                }
                val connection = address
                if (connection != null && error.isBlank()) {
                    JacredAdminWebView(connection, Modifier.weight(1f)) { error = it }
                } else if (error.isNotBlank()) {
                    Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Открываем SSH-туннель…", Modifier.padding(16.dp))
                }
            }
        }
    }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun JacredAdminWebView(address: JacredAdminAddress, modifier: Modifier, onError: (String) -> Unit) {
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
                        if (request.isForMainFrame) onError("Не удалось открыть веб-админку JacRed.")
                    }

                    override fun onReceivedHttpError(
                        view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) onError("Веб-админка JacRed ответила HTTP ${errorResponse.statusCode}.")
                    }
                }
                val browser = this
                cookies.setCookie(
                    address.origin, "${address.cookie}=${address.token}; Path=/; HttpOnly; SameSite=Strict",
                ) { stored ->
                    if (browser.tag == true) {
                        if (stored) browser.loadUrl("${address.origin}/settings")
                        else onError("Устройство не сохранило сеанс админки JacRed.")
                    }
                }
            }
        },
        onRelease = { browser ->
            browser.tag = false
            cookies.setCookie(address.origin, "${address.cookie}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
            browser.stopLoading()
            browser.destroy()
        },
    )
}
