package dev.mjwatch.core

import org.json.JSONObject

/** Only protocol fields enter diagnostics; never SSH output, addresses or credentials. */
data class InstallationProgress(
 val stage:String="checking", val state:String="running", val code:String="", val updated:Long=0
) {
 val index:Int get()=stages.indexOf(stage).let {if(stage=="awaiting_app_verification")4 else it}
 val terminal:Boolean get()=state in listOf("failed","cancelled")
 val attention:Boolean get()=terminal||state=="unreachable"
 val diagnostic:String get()="stage=$stage\nstate=$state\ncode=${code.ifBlank{"none"}}\nupdated=$updated"
 companion object {
  val stages=listOf("checking","installing_torrserver","installing_mjwatch","securing","verifying")
  fun from(json:JSONObject)=InstallationProgress(
   json.optString("stage").takeIf {it in stages+listOf("awaiting_app_verification","failed")} ?: "unknown",
   json.optString("state").takeIf {it in listOf("running","installed","failed","cancelled","unreachable")} ?: "unknown",
   json.optString("code").takeIf {it.matches(Regex("[A-Za-z0-9_]{1,80}"))}.orEmpty(),json.optLong("updated")
  )
 }
}
fun installationError(error:Throwable):String=when(error) {
 is IllegalArgumentException -> error.message?.takeIf {it in listOf(
  "Укажите IP или домен без протокола и пути","SSH-порт должен быть от 1 до 65535","Для установки нужен пользователь root",
  "Ключ поставки не совпадает с доверенным ключом приложения","Подпись серверной поставки не прошла проверку",
  "Обновите приложение для этой версии сервера","Нарушена целостность серверной поставки"
 )} ?: "Проверьте SSH-параметры и серверную поставку. Откройте подробности этапа."
 else -> safeError(error)
}
fun installationFailure(code:String):String=when(code) {
 "unsupported_os" -> "Автоустановка рассчитана на Debian и Ubuntu с apt и systemd."
 "unsupported_python" -> "Для автоустановки нужен Python 3.9 или новее."
 "missing_system_tools" -> "Не найдены необходимые системные команды: apt-get, systemctl, useradd, id или chown."
 "unsupported_system" -> "Нужен Linux amd64/arm64 с systemd."
 "insufficient_resources" -> "Недостаточно ресурсов: нужно 2 ГБ памяти и 2 ГиБ свободного места."
 "cancelled_at_safe_boundary" -> "Установка остановлена на безопасной границе. Её можно продолжить."
 "existing_unit_conflict","existing_config_conflict","existing_account_conflict","existing_release_conflict" -> "На сервере найдены существующие службы или файлы. Они не изменены: проверьте конфликт перед повтором."
 "supply_integrity","manifest_version" -> "Не прошла проверка серверной поставки. Проверьте версию приложения и поставки."
 "use_signed_update_for_version_change" -> "Сервер другой версии уже установлен. Используйте обновление в разделе обслуживания."
 "installation_identity_changed" -> "Адрес или архитектура отличаются от начатой установки. Проверьте исходные параметры."
 "command_failed_apt_get" -> "Не удалось установить системные пакеты. Проверьте доступ сервера к репозиториям и блокировку apt."
 "command_failed_openssl" -> "Не прошла настройка сертификатов или проверка подписи поставки."
 "command_failed_systemctl" -> "Не удалось запустить системную службу. Проверьте состояние служб на сервере."
 "command_failed_curl" -> "Не прошла HTTPS-проверка на сервере. Проверьте доступность службы и сертификат."
 "CalledProcessError" -> "Системная команда на сервере завершилась ошибкой. Проверьте пакеты, сеть и состояние служб."
 "auth_health_failed","auth_not_enforced" -> "Не прошла проверка защищённого подключения. Приложение не подключено."
 else -> "Установка не завершена. Проверьте состояние сервера и подробности этапа."
}
