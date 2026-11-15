package dev.mjwatch.designsystem

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Credentials

data class WatchState(val page:String="home", val query:String="", val results:List<Release> = emptyList(),
    val more:Boolean=false, val metadata:List<Metadata> = emptyList(), val selectedMetadata:Metadata?=null, val selected:Release?=null,val handle:TorrentHandle?=null,val busy:Boolean=false,val message:String="",val failures:List<String> = emptyList(),
    val originPage:String="home", val config:Config=Config(),val configured:Boolean=false,val focusKey:String="",val checkJackett:String="Не проверено",val checkTorr:String="Не проверено")
class WatchModel(app:Application): AndroidViewModel(app) {
    private val settings=Settings(app)
    val secrets=SecretStore(app)
    private val db=Room.databaseBuilder(app,WatchDatabase::class.java,"watch.db").build()
    val dao=db.dao()
    val library=dao.library().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val progress=dao.progress().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val mutable=MutableStateFlow(WatchState())
    val state=mutable.asStateFlow()
    var draftConfig:Config?=null; var draftKey="";var draftUser="";var draftPassword="";var draftMetadata=""
    private var cachedConfig:Config?=null;private var cachedRepositories:Pair<ReleaseRepository,StreamGateway>?=null
    private var checkRevision=0;private val checkJobs=mutableMapOf<Boolean,Job>()
    private var work:Job?=null; private var generation=0;private var pageIndex=0
    private fun update(block:(WatchState)->WatchState) { mutable.update(block) }
    init {viewModelScope.launch {
        var first=true
        combine(settings.config,settings.destination) {c,d->c to d}.collect {(c,d) ->
            update {it.copy(config=c,configured=c.demo||c.torrServer.isNotBlank(),page=if(!c.demo&&c.torrServer.isBlank()) "settings" else if(first) d else it.page)}
            first=false
        }
    }}
    fun discardDraft() {draftConfig=null;draftKey="";draftUser="";draftPassword="";draftMetadata=""}
    fun page(page:String) { cancel(); update { it.copy(page=page,message="") };if(page in setOf("home","search","library","settings")) viewModelScope.launch {settings.destination(page)} }
    fun back() { val s=state.value; cancel(); update { it.copy(page=when(s.page) { "files","preparing" -> "releases"; "releases" -> "detail"; "detail" -> s.originPage; else -> "home" },message="") } }
    fun cancel() { generation++; work?.cancel(); update { it.copy(busy=false) } }
    private fun repositories():Pair<ReleaseRepository,StreamGateway> {
        val c=state.value.config
        if(cachedConfig==c) cachedRepositories?.let {return it}
        if(c.demo) return DemoReleases to DemoGateway
        val client=guardedClient(c); val r=TorznabRepository(c,secrets.get("jackett:${c.jackett}"),client)
        return (r to TorrServerGateway(c,secrets.get("torrAuth:${c.torrServer}"),client,r)).also {cachedConfig=c;cachedRepositories=it}
    }
    fun search(query:String) {
        cancel(); pageIndex=0; val token=generation; update { it.copy(metadata=emptyList(),more=false,query=query,page="search",busy=query.trim().length>=2,message="",failures=emptyList()) }
        if(query.trim().length<2) { update { it.copy(results=emptyList()) }; return }
        work=viewModelScope.launch {
            delay(400)
            supervisorScope {
                if(state.value.config.metadataProvider=="tmdb") launch {
                    try {val items=withContext(Dispatchers.IO) {TmdbAdapter(secrets.get("tmdb"),guardedClient(state.value.config)).search(query.trim())};if(token==generation) update {it.copy(metadata=items)}}
                    catch(e:CancellationException) {throw e} catch(_:Exception) {if(token==generation) update {it.copy(metadata=emptyList())}}
                }
                try {
                    val result=withContext(Dispatchers.IO) {repositories().first.search(query.trim())}
                    if(token==generation) update {it.copy(results=result.releases,more=result.more,failures=result.failures,busy=false,message=if(result.releases.isEmpty()) if(result.failures.isEmpty()) "Ничего не найдено. Измените запрос." else "Не удалось выполнить поиск" else "")}
                } catch(e:CancellationException) {throw e} catch(e:Exception) {if(token==generation) update {it.copy(busy=false,message=safeError(e))}}
            }
        }
    }

    fun loadMore(retryFailed:Boolean=false) {
        val previous=state.value; if(previous.busy) return
        cancel();val token=generation;update {it.copy(busy=true)}
        work=viewModelScope.launch {
            try {
                val repository=repositories().first as? TorznabRepository ?: run {update {it.copy(busy=false)}; return@launch}
                val page=if(retryFailed) pageIndex else pageIndex+1
                val result=withContext(Dispatchers.IO) {repository.searchPage(previous.query,page,if(retryFailed) previous.failures.map {it.substringBefore(':')}.toSet() else null)}
                if(token==generation) {if(!retryFailed) pageIndex=page;update {it.copy(results=(previous.results+result.releases).distinctBy {it.hash ?: it.id},more=result.more,failures=result.failures,busy=false)}}
            } catch(e:CancellationException) {throw e} catch(e:Exception) {if(token==generation) update {it.copy(busy=false,message=safeError(e))}}
        }
    }
    fun metadata(item:Metadata) { update {it.copy(selectedMetadata=item)}; search(item.title) }
    fun open(release:Release,focusKey:String=release.id) {
        cancel(); update { it.copy(selected=release,page="detail",originPage=if(it.page in listOf("search","home","library")) it.page else it.originPage,focusKey=focusKey,handle=null,message="") }
        viewModelScope.launch(Dispatchers.IO) {
            secrets.put("release:${release.id}",release.json())
            dao.opened(LibraryEntry(release.id,release.title,release.source,System.currentTimeMillis()))
        }
    }
    fun restore(entry:LibraryEntry) { viewModelScope.launch(Dispatchers.IO) { val raw=secrets.get("release:${entry.id}"); if(raw.isNotBlank()) { val r=releaseFromJson(raw); withContext(Dispatchers.Main) { open(r) } } else withContext(Dispatchers.Main) {search(entry.title);update {it.copy(message="Сохранённая ссылка недоступна. Найдите раздачу заново.")}} } }
    fun bookmark() { val r=state.value.selected ?: return; viewModelScope.launch(Dispatchers.IO) { dao.toggleBookmark(LibraryEntry(r.id,r.title,r.source)) } }
    fun remove(entry:LibraryEntry,bookmarkOnly:Boolean=false) { viewModelScope.launch(Dispatchers.IO) { if(bookmarkOnly) dao.put(entry.copy(bookmarked=false)) else if(entry.bookmarked) dao.put(entry.copy(openedAt=0)) else dao.delete(entry.id) } }
    fun undo(entry:LibraryEntry) { viewModelScope.launch(Dispatchers.IO) { dao.put(entry) } }
    fun prepare() {
        cancel(); val token=generation; val release=state.value.selected ?: return
        update { it.copy(page="preparing",busy=true,message="Передаём раздачу на сервер…") }
        work=viewModelScope.launch {
            try { val handle=withContext(Dispatchers.IO) { withTimeout(60_000) {
                val gateway=repositories().second; var h=state.value.handle ?: gateway.add(release)
                // Keep the acknowledged handle for safe retries; never add twice after metadata timeout.
                if(token==generation) update { it.copy(handle=h,message="Ожидаем список файлов…") }
                var polls=0
                while(h.files.isEmpty()) { delay(if(polls++<10) 1000 else 2000); h=gateway.status(h.hash) }
                h
            } }
                if(token==generation) update { it.copy(handle=handle,page="files",busy=false,message="") }
            } catch(e:CancellationException) { if(e is TimeoutCancellationException && token==generation) update { it.copy(busy=false,message="Сервер не подготовил файлы за 60 секунд. Повторить или выбрать другую раздачу.") } else throw e
            } catch(e:Exception) { if(token==generation) update { it.copy(busy=false,message=safeError(e)) } }
        }
    }
    fun playerClosed() {update {it.copy(page="detail")}}
    fun stream(file:TorrentFile): StreamRequest = repositories().second.stream(state.value.handle!!,file)
    fun check(jackett:Boolean,c:Config,key:String,user:String,password:String) {
        val revision=checkRevision;checkJobs[jackett]?.cancel()
        checkJobs[jackett]=viewModelScope.launch {
            try { val result=withContext(Dispatchers.IO) {
                if(c.demo) { if(jackett) DemoReleases.check() else DemoGateway.check() } else {
                    if(jackett) endpoint(c.jackett) else endpoint(c.torrServer); val scoped=if(jackett) c.copy(torrServer="") else c.copy(jackett="",indexers="",torznabPaths=emptyMap());val client=guardedClient(scoped); val r=TorznabRepository(scoped,key,client)
                    if(jackett) r.check() else TorrServerGateway(scoped,if(user.isBlank()) "" else Credentials.basic(user,password),client,r).check()
                }
            }; if(revision==checkRevision) update { if(jackett) it.copy(checkJackett=result) else it.copy(checkTorr=result) }
            } catch(e:CancellationException) {throw e} catch(e:Exception) { if(revision==checkRevision) update { if(jackett) it.copy(checkJackett=safeError(e)) else it.copy(checkTorr=safeError(e)) } }
        }
    }
    fun invalidateChecks() {checkRevision++;checkJobs.values.forEach {it.cancel()}; update { it.copy(checkJackett="Не проверено",checkTorr="Не проверено") } }
    fun validate(c:Config) { if(!c.demo) { if(c.jackett.isNotBlank()) endpoint(c.jackett); if(c.torrServer.isNotBlank()) endpoint(c.torrServer); require(c.torrServer.isNotBlank()) { "Укажите TorrServer" } } }
    fun save(c:Config,key:String,user:String,password:String,metadataToken:String="") {
        viewModelScope.launch { try {
            android.util.Log.i("MJWatch", "Saving configuration (demo=${c.demo})"); validate(c)
            withContext(Dispatchers.IO) { secrets.put("tmdb",metadataToken); secrets.put("jackett:${c.jackett}",key); secrets.put("torrAuth:${c.torrServer}",if(user.isBlank()) "" else Credentials.basic(user,password)); secrets.put("torrUser:${c.torrServer}",user); secrets.put("torrPassword:${c.torrServer}",password) }
            android.util.Log.i("MJWatch", "Credentials saved"); cachedRepositories=null;settings.destination("home");settings.save(c); android.util.Log.i("MJWatch", "Configuration saved"); draftConfig=null; draftKey=""; draftUser=""; draftPassword=""; draftMetadata=""; update { it.copy(page="home",message="Настройки сохранены · подключения можно проверить отдельно") }
        } catch(e:Exception) { update { it.copy(message=safeError(e)) } } }
    }
    override fun onCleared() { db.close(); super.onCleared() }
}
