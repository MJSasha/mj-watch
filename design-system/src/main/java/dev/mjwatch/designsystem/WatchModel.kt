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
    val catalog:List<Metadata> = emptyList(),val catalogBusy:Boolean=false,val catalogMessage:String="",val metadataCached:Boolean=false,val more:Boolean=false, val metadata:List<Metadata> = emptyList(), val selectedMetadata:Metadata?=null,val resumePath:String?=null, val selected:Release?=null,val handle:TorrentHandle?=null,val busy:Boolean=false,val message:String="",val failures:List<String> = emptyList(),
    val removed:LibraryEntry?=null,val originPage:String="home", val config:Config=Config(),val configured:Boolean=false,val focusKey:String="",val checkJackett:String="Не проверено",val checkTorr:String="Не проверено")
class WatchModel(app:Application): AndroidViewModel(app) {
    val updates=UpdateManager(app,viewModelScope)
    private val settings=Settings(app)
    val secrets=SecretStore(app)
    private val db=Room.databaseBuilder(app,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2).enableMultiInstanceInvalidation().build()
    val dao=db.dao()
    val library=dao.library().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val progress=dao.progress().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val mutable=MutableStateFlow(WatchState())
    val state=mutable.asStateFlow()
    var draftConfig:Config?=null; var draftKey="";var draftUser="";var draftPassword="";var draftMetadata=""
    private var cachedConfig:Config?=null;private var cachedRepositories:Pair<ReleaseRepository,StreamGateway>?=null
    private var catalogJob:Job?=null
    private var checkRevision=0;private val checkJobs=mutableMapOf<Boolean,Job>()
    private var work:Job?=null; private var generation=0;private var pageIndex=0
    private fun update(block:(WatchState)->WatchState) { mutable.update(block) }
    init {viewModelScope.launch {
        var first=true;var provider:String?=null
        combine(settings.config,settings.destination) {c,d->c to d}.collect {(c,d) ->
            update {it.copy(config=c,configured=c.demo||c.torrServer.isNotBlank(),page=if(first) if(!c.demo&&c.torrServer.isBlank()) "settings" else d else it.page)}
            if(provider!=c.metadataProvider) {provider=c.metadataProvider;refreshCatalog()}
            first=false
        }
    }}
    fun discardDraft() {draftConfig=null;draftKey="";draftUser="";draftPassword="";draftMetadata=""}
    fun page(page:String) { cancel(); update { it.copy(page=page,message="") };if(page in setOf("home","search","library","history","settings")) viewModelScope.launch {settings.destination(page)} }
    fun back() {
        val s=state.value;cancel()
        val target=when {s.page.startsWith("settings-")->"settings";s.page=="connections" -> "settings";s.page in setOf("files","preparing") -> "releases";s.page=="releases" -> "detail";s.page=="detail" -> s.originPage;else -> "home"}
        update {it.copy(page=target,message="")}
        if(target in setOf("home","search","library","history","settings")) viewModelScope.launch {settings.destination(target)}
    }
    fun cancel() { generation++; work?.cancel(); update { it.copy(busy=false) } }
    private fun repositories():Pair<ReleaseRepository,StreamGateway> {
        val c=state.value.config
        if(cachedConfig==c) cachedRepositories?.let {return it}
        if(c.demo) return DemoReleases to DemoGateway
        val client=guardedClient(c); val r=TorznabRepository(c,secrets.get("jackett:${c.jackett}"),client)
        return (r to TorrServerGateway(c,secrets.get("torrAuth:${c.torrServer}"),client,r)).also {cachedConfig=c;cachedRepositories=it}
    }
    private fun metadataRepository():MetadataRepository {val c=state.value.config;return MetadataRepository(TmdbAdapter(secrets.get("tmdb"),guardedClient(c)),dao)}
    fun refreshCatalog(force:Boolean=false) {
        catalogJob?.cancel()
        if(state.value.config.metadataProvider!="tmdb") {update {it.copy(catalog=emptyList(),catalogBusy=false,catalogMessage="")};return}
        update {it.copy(catalogBusy=true,catalogMessage="")}
        catalogJob=viewModelScope.launch {try {
            val result=withContext(Dispatchers.IO) {metadataRepository().home(force)}
            update {it.copy(catalog=result.items,catalogBusy=false,catalogMessage=if(result.cached) "Каталог из кеша" else "")}
        } catch(e:CancellationException) {throw e} catch(e:Exception) {update {it.copy(catalogBusy=false,catalogMessage="Каталог недоступен. Прямой поиск раздач работает независимо.")}}}
    }
    fun search(query:String) {
        cancel(); pageIndex=0; val token=generation; update { it.copy(selectedMetadata=it.selectedMetadata?.takeIf {m->m.title==query.trim()},metadata=emptyList(),results=emptyList(),more=false,query=query,page="search",busy=query.trim().length>=2,message="",failures=emptyList()) }
        if(query.trim().length<2) { update { it.copy(results=emptyList()) }; return }
        work=viewModelScope.launch {
            delay(400)
            supervisorScope {
                if(state.value.config.metadataProvider=="tmdb") launch {
                    try {val result=withContext(Dispatchers.IO) {metadataRepository().search(query.trim())};if(token==generation) update {it.copy(metadata=result.items,metadataCached=result.cached)}}
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
    fun metadata(item:Metadata,focusKey:String=item.id) {
        cancel();update {it.copy(selectedMetadata=item,selected=null,resumePath=null,page="detail",originPage=if(it.page in setOf("home","search","library","history")) it.page else it.originPage,focusKey=focusKey,handle=null)}
        viewModelScope.launch(Dispatchers.IO) {dao.put(CachedMetadata(item.id,item.json(),System.currentTimeMillis()));dao.opened(LibraryEntry(item.id,item.title,"TMDB",System.currentTimeMillis(),metadataId=item.id))}
    }
    fun findReleases() {val item=state.value.selectedMetadata ?: return;search(item.title);update {it.copy(page="releases")}}

    fun open(release:Release,focusKey:String=release.id,metadata:Metadata?=state.value.selectedMetadata?.takeIf {state.value.page in setOf("releases","search","detail")}) {
        cancel(); update { it.copy(selectedMetadata=metadata,selected=release,resumePath=null,page="detail",originPage=if(it.page in listOf("search","home","library","history")) it.page else it.originPage,focusKey=if(it.page=="releases"&&metadata!=null) it.focusKey else focusKey,handle=null,message="") }
        viewModelScope.launch(Dispatchers.IO) {
            secrets.put("release:${release.id}",release.json())
            dao.opened(LibraryEntry(release.id,release.title,release.source,System.currentTimeMillis(),metadataId=metadata?.id))
        }
    }
    fun restore(entry:LibraryEntry) {viewModelScope.launch(Dispatchers.IO) {
        val metadata=entry.metadataId?.let {dao.metadata(it)?.let {metadataFromJson(it.json)}}
        if(entry.id.startsWith("tmdb:")&&metadata!=null) {withContext(Dispatchers.Main) {this@WatchModel.metadata(metadata)};return@launch}
        val raw=secrets.get("release:${entry.id}")
        if(raw.isNotBlank()) {val r=releaseFromJson(raw);withContext(Dispatchers.Main) {open(r,metadata=metadata.takeIf {state.value.config.metadataProvider!="none"})}}
        else withContext(Dispatchers.Main) {search(entry.title);update {it.copy(message="Сохранённая ссылка недоступна. Найдите раздачу заново.")}}
    }}
    fun resume(saved:Progress) {
        android.util.Log.d("MJWatch","Resume requested")
        if(state.value.busy) return
        cancel();val token=generation;val context=state.value.selectedMetadata;val focus=state.value.focusKey
        update {it.copy(busy=true,message="")}
        work=viewModelScope.launch {
            try {
                val raw=withContext(Dispatchers.IO) {secrets.get("release:${saved.releaseId}")}
                require(raw.isNotBlank()) {"Сохранённая раздача недоступна. Выберите другую."}
                if(token==generation) {open(releaseFromJson(raw),focusKey=focus,metadata=context);update {it.copy(resumePath=saved.path)};android.util.Log.d("MJWatch","Resume staged");prepare()}
            } catch(e:CancellationException) {throw e} catch(e:Exception) {if(token==generation) update {it.copy(busy=false,message=safeError(e))}}
        }
    }
    fun consumeResume(missing:Boolean) {update {it.copy(resumePath=null,message=if(missing) "Прежний файл не найден. Выберите видео вручную." else "")}}
    fun isFileSelectionActive(hash:String?,releaseId:String?):Boolean {
        val s=state.value;val ok=s.page=="files"&&s.handle?.hash==hash&&s.selected?.id==releaseId
        if(!ok) android.util.Log.d("MJWatch","Ignored stale playback launch: page=${s.page}, sameHandle=${s.handle?.hash==hash}, sameRelease=${s.selected?.id==releaseId}")
        return ok
    }
    fun bookmark() {
        val m=state.value.selectedMetadata;val r=state.value.selected
        val entry=if(m!=null) LibraryEntry(m.id,m.title,"TMDB",metadataId=m.id) else r?.let {LibraryEntry(it.id,it.title,it.source)} ?: return
        viewModelScope.launch(Dispatchers.IO) {dao.toggleBookmark(entry)}
    }
    fun remove(entry:LibraryEntry,bookmarkOnly:Boolean=false) { viewModelScope.launch(Dispatchers.IO) { if(bookmarkOnly) dao.put(entry.copy(bookmarked=false)) else if(entry.bookmarked) dao.put(entry.copy(openedAt=0)) else dao.delete(entry.id); update {it.copy(removed=entry)} } }
    fun undo(entry:LibraryEntry) {update {it.copy(removed=null)};viewModelScope.launch(Dispatchers.IO) {dao.put(entry)}}
    fun clearUndo() {update {it.copy(removed=null)}}
    fun prepare() {
        if(state.value.busy) return
        cancel(); val token=generation; val release=state.value.selected ?: return
        update { it.copy(page="preparing",busy=true,message="Передаём раздачу на сервер…") }
        work=viewModelScope.launch {
            try { val handle=withContext(Dispatchers.IO) { withTimeout(60_000) {
                val gateway=repositories().second
                val previous=state.value.handle
                val known=previous?.hash ?: release.hash
                var h=if(known==null) gateway.add(release) else try {gateway.status(known)} catch(e:HttpFailure) {if(e.status==404) gateway.add(release) else throw e}
                // Keep the acknowledged handle for safe retries; never add twice after metadata timeout.
                if(token==generation) {update {it.copy(handle=h,selected=it.selected?.copy(hash=h.hash),message="Ожидаем список файлов…")};secrets.put("release:${release.id}",release.copy(hash=h.hash).json())}
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
        if(state.value.busy) return
        val target=if(state.value.page.startsWith("settings-")) "settings" else "home"
        update {it.copy(busy=true,message="")}
        viewModelScope.launch { try {
            android.util.Log.i("MJWatch", "Saving configuration (demo=${c.demo})"); validate(c)
            withContext(Dispatchers.IO) { secrets.put("tmdb",metadataToken); secrets.put("jackett:${c.jackett}",key); secrets.put("torrAuth:${c.torrServer}",if(user.isBlank()) "" else Credentials.basic(user,password)); secrets.put("torrUser:${c.torrServer}",user); secrets.put("torrPassword:${c.torrServer}",password) }
            android.util.Log.i("MJWatch", "Credentials saved"); cachedRepositories=null;settings.destination(target);settings.save(c); android.util.Log.i("MJWatch", "Configuration saved"); draftConfig=null; draftKey=""; draftUser=""; draftPassword=""; draftMetadata=""; update { it.copy(page=target,busy=false,message="Настройки сохранены · подключения можно проверить отдельно") }
        } catch(e:Exception) { update { it.copy(busy=false,message=safeError(e)) } } }
    }
    override fun onCleared() { db.close(); super.onCleared() }
}
