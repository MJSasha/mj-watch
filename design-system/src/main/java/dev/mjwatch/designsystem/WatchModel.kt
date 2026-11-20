package dev.mjwatch.designsystem

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Credentials

data class WatchState(val initializing:Boolean=true,val legacyHistoryRecords:Int=0,val page:String="home", val query:String="", val results:List<Release> = emptyList(),
    val catalogSection:CatalogSection=CatalogSection.FOR_YOU,val catalog:List<Metadata> = emptyList(),val catalogBusy:Boolean=false,val catalogMessage:String="",val metadataCached:Boolean=false,val more:Boolean=false, val metadata:List<Metadata> = emptyList(), val selectedMetadata:Metadata?=null,val resumePath:String?=null,val releaseSeason:Int?=null,val releaseEpisode:Int?=null, val selected:Release?=null,val handle:TorrentHandle?=null,val busy:Boolean=false,val message:String="",val failures:List<String> = emptyList(),
    val episodeTargetId:String?=null,val episodeDownload:Boolean=false,
    val remotePlayback:Boolean=false,val downloadSelection:Boolean=false,val openDownloadsRequested:Boolean=false,val removed:List<LibraryEntry>?=null,val removedBookmarks:Boolean=false,val originPage:String="home", val config:Config=Config(),val configured:Boolean=false,val focusKey:String="",val checkJackett:String="Не проверено",val checkTorr:String="Не проверено")
class WatchModel(app:Application): AndroidViewModel(app) {
    private var cachedEpisodeCatalog: EpisodeCatalog? = null
    internal fun episodeCatalog(id: String, config: Config): EpisodeCatalog =
        cachedEpisodeCatalog?.takeIf { it.id == id && it.config == config }
            ?: EpisodeCatalog(id, config).also { cachedEpisodeCatalog = it }

    val isTv=app.packageName.endsWith(".tv")
    val remote=dev.mjwatch.playback.RemotePlaybackService.state
    val canStartPlayback get()=isTv||!state.value.remotePlayback||remote.value.canLaunch&&remote.value.profileKey==state.value.config.profileKey
    val updates=UpdateManager(app,viewModelScope)
    private val settings=Settings(app)
    val secrets=SecretStore(app)
    private val db=Room.databaseBuilder(app,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).enableMultiInstanceInvalidation().build()
    val dao=db.dao()
    data class LibrarySnapshot(val context:String="",val entries:List<LibraryEntry> = emptyList(),val positions:List<Progress> = emptyList(),val metadata:Map<String,Metadata> = emptyMap()) {
        val titles get()=libraryTitles(entries,metadata)
    }
    private fun snapshot(c:Config,entries:List<LibraryEntry>,positions:List<Progress>,cache:List<CachedMetadata>):LibrarySnapshot {
        val byId=cache.associateBy {it.id}
        val metadata=entries.mapNotNull {it.metadataId}.distinct().mapNotNull {id->
            (byId["${if(c.managed)c.scope else "direct"}:$id"] ?: if(!c.managed)byId[id] else null)
                ?.let {runCatching {id to metadataFromJson(it.json)}.getOrNull()}
        }.toMap()
        return LibrarySnapshot(c.profileKey,entries,positions,metadata)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val scopedLibrary:StateFlow<LibrarySnapshot> =settings.config.flatMapLatest {c->
        if(c.historyEnabled) combine(db.syncDao().entities(c.scope),dao.metadataEntries()) {rows,cache->
            val favorites=rows.filter {it.kind=="favorite"}.mapNotNull {runCatching {it.libraryEntry()}.getOrNull()}.filter {it.bookmarked}.associateBy {it.id}
            val openings=rows.filter {it.kind=="opening"}.mapNotNull {runCatching {it.libraryEntry()}.getOrNull()}
            snapshot(c,(openings.map {it.copy(bookmarked=favorites[it.id]?.bookmarked==true)}+favorites.values.filter {f->openings.none {it.id==f.id}}).sortedByDescending {it.openedAt},
                rows.filter {it.kind=="progress"}.mapNotNull {runCatching {it.progress()}.getOrNull()},cache)
        } else if(c.managed) flowOf(LibrarySnapshot(c.profileKey))
        else combine(dao.library(),dao.metadataEntries()) {rows,cache->snapshot(c,rows.filter {it.bookmarked}.map {it.copy(openedAt=0)},emptyList(),cache)}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),LibrarySnapshot())
    val library:StateFlow<List<LibraryEntry>> =scopedLibrary.map {it.entries}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val progress:StateFlow<List<Progress>> =scopedLibrary.map {it.positions}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val mutable=MutableStateFlow(WatchState())
    val state=mutable.asStateFlow()
    val managed=ManagedController(app,viewModelScope,settings,secrets,db,{state.value.config})
    val invitation=InvitationController(app,viewModelScope,secrets,managed,{state.value.config})
    fun openRemoteContent(releaseId:String,title:String,metadataId:String) {
        viewModelScope.launch {state.first {!it.initializing}
        if(metadataId.matches(Regex("tmdb:(movie|tv):[0-9]+")))metadata(Metadata(metadataId,title,null,null,kind=if(metadataId.startsWith("tmdb:tv:"))"tv" else "movie"))
        else if(releaseId.isNotBlank())open(Release(releaseId,title,"MJWatch"))
        }
    }
    fun openInvitation(raw:String){invitation.open(raw);page("invitation-register")}
    var draftConfig:Config?=null; var draftKey="";var draftUser="";var draftPassword="";var draftMetadata=""
    private var cachedConfig:Config?=null;private var cachedRepositories:Pair<ReleaseRepository,StreamGateway>?=null
    private var catalogJob:Job?=null
    private var checkRevision=0;private val checkJobs=mutableMapOf<Boolean,Job>()
    private var work:Job?=null; private var generation=0;private var pageIndex=0
    private fun update(block:(WatchState)->WatchState) { mutable.update(block) }
    init {viewModelScope.launch {
        if(!settings.legacyHistoryHandled.first()){val records=dao.library().first().count {it.openedAt>0}+dao.progress().first().size;update {it.copy(legacyHistoryRecords=records)}}
        var first=true;var provider:String?=null
        combine(settings.config,settings.destination) {c,d->c to d}.collect {(c,d) ->
            val changed=state.value.config.profileKey!=c.profileKey
            if(changed){discardDraft();this@WatchModel.cancel();catalogJob?.cancel();cachedRepositories=null;update{it.copy(query="",results=emptyList(),episodeTargetId=null,episodeDownload=false,releaseSeason=null,releaseEpisode=null,metadata=emptyList(),catalog=emptyList(),selected=null,selectedMetadata=null,handle=null,resumePath=null,removed=null,message="",focusKey="",openDownloadsRequested=false)}}
            update {it.copy(initializing=false,config=c,configured=c.demo||c.torrServer.isNotBlank()||c.historyEnabled,page=if(first&&it.page!="invitation-register") if(!c.demo&&c.torrServer.isBlank()&&!c.historyEnabled) "connection-choice" else d else it.page)}
            if(provider!="${c.profileKey}:${c.metadataProvider}") {provider="${c.profileKey}:${c.metadataProvider}";refreshCatalog()}
            first=false
        }
    }}
    init {viewModelScope.launch {
        combine(settings.config,scopedLibrary) {c,snapshot->Triple(c,snapshot.context,snapshot.entries.mapNotNull {it.metadataId}.distinct())}.distinctUntilChanged().collectLatest {(c,context,ids)->
            if(context==c.profileKey&&c.metadataProvider=="tmdb"&&!c.demo) for(id in ids) {
                try {withContext(Dispatchers.IO) {metadataRepository(c).details(id)}}
                catch(e:CancellationException){throw e}catch(_:Exception) { /* Cached title remains available offline. */ }
            }
        }
    }}
    init {viewModelScope.launch {
        remote.map {if(it.connected)it.target?.let {t->t.id+":"+t.instance} else null}.distinctUntilChanged().drop(1).collect {
            if(!isTv&&state.value.remotePlayback&&state.value.page in setOf("preparing","files")) {cancel();update {s->s.copy(page="detail",message="Адресат изменился. Запустите видео на выбранном ТВ.")}}
        }
    }}
    fun preferencesChanged(){val c=state.value.config;val prefs=getApplication<Application>().getSharedPreferences("playback-preferences",0);managed.storePreferences(c);if(c.historyEnabled)viewModelScope.launch(Dispatchers.IO){managed.record(c,"preferences","profile",org.json.JSONObject().put("quality",prefs.getString("quality","Все")).put("audioLanguage",prefs.getString("audio-language",null)).put("subtitleLanguage",if(prefs.getBoolean("subtitles-off",false))"off" else prefs.getString("subtitle-language",null)))}}
    fun legacyHandled(){update {it.copy(legacyHistoryRecords=0)}}
    fun discardDraft() {draftConfig=null;draftKey="";draftUser="";draftPassword="";draftMetadata=""}
    fun page(page:String) { detailOrigin=null; cancel(); update { it.copy(page=page,message="",openDownloadsRequested=false) };if(page in setOf("home","search","library","history","settings")) viewModelScope.launch {settings.destination(page)} }
    fun openDownloads() {
        if(isTv) return
        detailOrigin=null
        cancel()
        update {it.copy(page="library",message="",openDownloadsRequested=true)}
        viewModelScope.launch {settings.destination("library")}
    }
    fun consumeOpenDownloads() {update {it.copy(openDownloadsRequested=false)}}
    fun back() {
        if(state.value.page=="invitation-register"){if(invitation.state.value.busy)return;invitation.dismiss()}
        val s=state.value;cancel()
        if(s.page in setOf("preparing","files") && detailOrigin!=null) {
            playerClosed()
            return
        }
        val target=when {s.page=="server-update"->"server-maintenance";s.page in setOf("server-user-invite","server-user-devices")->"server-user";s.page in setOf("server-user","server-user-create")->"server-users";s.page in setOf("managed-devices","managed-pairing")->"managed-account";s.page.startsWith("settings-")||s.page in setOf("connection-choice","managed-login","managed-account","server-overview","server-install")->"settings";s.page.startsWith("server-")->"server-overview";s.page=="connections" -> "settings";s.page in setOf("files","preparing") -> "releases";s.page=="releases" -> "detail";s.page=="detail" -> s.originPage;else -> "home"}
        update {it.copy(page=target,message="",openDownloadsRequested=false)}
        if(target in setOf("home","search","library","history","settings")) viewModelScope.launch {settings.destination(target)}
    }
    fun cancel() { generation++; work?.cancel(); update { it.copy(busy=false) } }
    private fun repositories():Pair<ReleaseRepository,StreamGateway> {
        val c=state.value.config
        if(cachedConfig==c) cachedRepositories?.let {return it}
        if(c.demo) return DemoReleases to DemoGateway
        val client=if(c.managed&&runCatching{endpoint(c.jackett).origin()==endpoint(c.managedUrl).origin()}.getOrDefault(false)) managedClient(c,secrets) else guardedClient(c); val r=TorznabRepository(c,secrets.get("jackett:${c.jackett}"),client)
        return (r to if(c.managed) ManagedGateway(ManagedApi(c,secrets),r) else TorrServerGateway(c,secrets.get("torrAuth:${c.torrServer}"),client,r)).also {cachedConfig=c;cachedRepositories=it}
    }
    private fun metadataRepository(c:Config=state.value.config):MetadataRepository {return MetadataRepository(if(c.managed) ManagedMetadata(ManagedApi(c,secrets)) else TmdbAdapter(secrets.get("tmdb"),guardedClient(c)),dao,if(c.managed) c.scope else "direct")}
    suspend fun loadTitleFacts(item: Metadata, config: Config) {
        if (config.demo || config.metadataProvider != "tmdb" || !item.id.matches(Regex("tmdb:(movie|tv):[0-9]+"))) return
        val detail = withContext(Dispatchers.IO) { metadataRepository(config).titleFacts(item.id) } ?: return
        update { current ->
            val selected = current.selectedMetadata
            if (current.config != config || selected?.id != item.id || current.page != "detail") current
            else current.copy(selectedMetadata = selected.copy(
                genre = detail.genre ?: selected.genre, runtimeMinutes = detail.runtimeMinutes ?: selected.runtimeMinutes
            ))
        }
    }
    fun catalogSection(section:CatalogSection) {
        if(state.value.catalogSection==section)return
        update {it.copy(catalogSection=section,catalog=emptyList(),focusKey="")}
        refreshCatalog()
    }
    fun refreshCatalog(force:Boolean=false) {
        catalogJob?.cancel()
        if(state.value.config.metadataProvider!="tmdb") {update {it.copy(catalog=emptyList(),catalogBusy=false,catalogMessage="")};return}
        update {it.copy(catalogBusy=true,catalogMessage="")}
        catalogJob=viewModelScope.launch {try {
            val result=withContext(Dispatchers.IO) {metadataRepository().home(force,state.value.catalogSection)}
            update {it.copy(catalog=result.items,catalogBusy=false,catalogMessage=if(result.cached) "Каталог из кеша" else "")}
        } catch(e:CancellationException) {throw e} catch(e:Exception) {update {it.copy(catalogBusy=false,catalogMessage=safeError(e)+". Поиск раздач и просмотр работают независимо.")}}}
    }
    fun search(query:String) {
        cancel(); pageIndex=0; val token=generation; update { it.copy(selectedMetadata=it.selectedMetadata?.takeIf {m->m.title==query.trim()},metadata=emptyList(),results=emptyList(),more=false,episodeTargetId=null,episodeDownload=false,releaseSeason=null,releaseEpisode=null,query=query,page="search",busy=query.trim().length>=2,message="",failures=emptyList()) }
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
                    if(token==generation) update {it.copy(results=result.releases,more=result.more,failures=result.failures,message=if(result.releases.isEmpty()) if(result.failures.isEmpty()) "Ничего не найдено. Измените запрос." else "Не удалось выполнить поиск" else "")}
                } catch(e:CancellationException) {throw e} catch(e:Exception) {if(token==generation) update {it.copy(message=safeError(e))}}
            }
            if(token==generation) update {it.copy(busy=false)}
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
    fun metadata(item:Metadata,focusKey:String=item.id,cache:Boolean=true) {
        detailOrigin=null
        cancel();update {it.copy(selectedMetadata=item,selected=null,episodeTargetId=null,episodeDownload=false,remotePlayback=false,downloadSelection=false,resumePath=null,releaseSeason=null,releaseEpisode=null,page="detail",originPage=if(it.page in setOf("home","search","library","history")) it.page else it.originPage,focusKey=focusKey,handle=null)}
        val c=state.value.config
        viewModelScope.launch(Dispatchers.IO) {if(c.historyEnabled){if(cache)dao.put(CachedMetadata("${c.scope}:${item.id}",item.json(),System.currentTimeMillis()));managed.record(c,"opening",item.id,LibraryEntry(item.id,item.title,"TMDB",System.currentTimeMillis(),metadataId=item.id).syncJson())}}
    }
    private data class DetailOrigin(val release:Release?, val focusKey:String)
    private var detailOrigin:DetailOrigin?=null
    private var detailSourceKey:Pair<Config,String>?=null
    private var detailSourceResult:SearchResult?=null

    /** Look up sources for the detail tab without replacing the user's search results. */
    suspend fun titleReleases(title:String,refresh:Boolean=false):SearchResult {
        val key=state.value.config to title
        if(!refresh && detailSourceKey==key) detailSourceResult?.let {return it}
        val repository=repositories().first
        return withContext(Dispatchers.IO) {repository.search(title)}.also {result ->
            detailSourceKey=key
            detailSourceResult=result
        }
    }
    fun findReleases() {val item=state.value.selectedMetadata ?: return;search(item.title);update {it.copy(page="releases")}}
    fun findEpisodeReleases(item:Metadata,season:Int,episode:Int,targetId:String?=null,download:Boolean=false) {
        search(item.title)
        update {it.copy(selectedMetadata=item,page="releases",releaseSeason=season,releaseEpisode=episode,episodeTargetId=targetId,episodeDownload=download,resumePath=null)}
    }

    fun open(release:Release,focusKey:String=release.id,metadata:Metadata?=state.value.selectedMetadata?.takeIf {state.value.page in setOf("releases","search","detail")}) {
        cancel(); update { it.copy(selectedMetadata=metadata,selected=release,episodeTargetId=null,episodeDownload=false,remotePlayback=false,downloadSelection=false,resumePath=null,page="detail",originPage=if(it.page in listOf("search","home","library","history")) it.page else it.originPage,focusKey=if(it.page=="releases"&&metadata!=null) it.focusKey else focusKey,handle=null,message="") }
        val c=state.value.config
        viewModelScope.launch(Dispatchers.IO) {if(c.historyEnabled) {
            secrets.put("release:${c.scope}:${release.id}",release.json())
            managed.record(c,"opening",release.id,LibraryEntry(release.id,release.title,release.source,System.currentTimeMillis(),metadataId=metadata?.id).syncJson())
        }}
    }
    fun restore(entry:LibraryEntry) {cancel();val c=state.value.config;val token=generation;viewModelScope.launch(Dispatchers.IO) {
        val metadata=entry.metadataId?.let {id->
            (dao.metadata("${if(c.managed)c.scope else "direct"}:$id") ?: if(!c.managed)dao.metadata(id) else null)?.let {metadataFromJson(it.json)}
                ?: Metadata(id,entry.title,null,null,kind=if(id.startsWith("tmdb:tv:"))"tv" else "movie")
        }
        val raw=secrets.get("release:${c.scope}:${entry.id}").ifBlank {if(!c.managed)secrets.get("release:${entry.id}") else ""}
        withContext(Dispatchers.Main){if(state.value.config.profileKey!=c.profileKey||generation!=token)return@withContext
            if(metadata!=null)this@WatchModel.metadata(metadata,cache=false)
            else if(raw.isNotBlank())open(releaseFromJson(raw),metadata=metadata.takeIf {c.metadataProvider!="none"})
            else {search(entry.title);update {it.copy(message="Сохранённая ссылка недоступна. Найдите раздачу заново.")}}
        }
    }}
    fun resume(saved:Progress,onTv:Boolean=false) {
        detailOrigin=null
        if(onTv&&(!remote.value.canLaunch||remote.value.profileKey!=state.value.config.profileKey))return
        android.util.Log.d("MJWatch","Resume requested")
        if(state.value.busy) return
        cancel();val token=generation;val route=remote.value.target?.let {it.id+":"+it.instance};val c=state.value.config;val context=state.value.selectedMetadata;val focus=state.value.focusKey
        update {it.copy(busy=true,remotePlayback=onTv,message="")}
        work=viewModelScope.launch {
            try {
                val release=withContext(Dispatchers.IO) {
                    val raw=secrets.get("release:${c.scope}:${saved.releaseId}")
                    raw.takeIf {it.isNotBlank()}?.let {runCatching {releaseFromJson(it)}.getOrNull()}
                        ?: saved.resumeRelease(context?.title ?: state.value.selected?.title ?: saved.releaseId,"История просмотра")
                }
                checkNotNull(release) {"Сохранённая раздача недоступна. Выберите другую."}
                if(token==generation) {
                    if(canStartPlayback&&(!onTv||isTv||route==remote.value.target?.let {it.id+":"+it.instance})) {
                        open(release,focusKey=focus,metadata=context);update {it.copy(resumePath=saved.path,remotePlayback=onTv)};prepare()
                    } else update {it.copy(busy=false,message="Адресат изменился. Запустите видео на выбранном ТВ.")}
                }
            } catch(e:CancellationException) {throw e} catch(e:Exception) {if(token==generation) update {it.copy(busy=false,message=safeError(e))}}
        }
    }
    fun consumeResume(missing:Boolean) {update {it.copy(resumePath=null,message=if(missing) "Прежний файл не найден. Выберите видео вручную." else "")}}
    fun isFileSelectionActive(hash:String?,releaseId:String?):Boolean {
        val s=state.value;val ok=canStartPlayback&&s.page=="files"&&s.handle?.hash==hash&&s.selected?.id==releaseId
        if(!ok) android.util.Log.d("MJWatch","Ignored stale playback launch: page=${s.page}, sameHandle=${s.handle?.hash==hash}, sameRelease=${s.selected?.id==releaseId}")
        return ok
    }
    fun bookmark() {
        val m=state.value.selectedMetadata;val r=state.value.selected
        val entry=if(m!=null) LibraryEntry(m.id,m.title,"TMDB",metadataId=m.id) else r?.let {LibraryEntry(it.id,it.title,it.source)} ?: return
        val c=state.value.config
        viewModelScope.launch(Dispatchers.IO) {if(c.historyEnabled) {val old=scopedLibrary.value.takeIf{it.context==c.profileKey}?.entries?.firstOrNull {it.id==entry.id};managed.record(c,"favorite",entry.id,entry.copy(bookmarked=old?.bookmarked!=true).syncJson())} else {m?.let {dao.put(CachedMetadata("direct:${it.id}",it.json(),System.currentTimeMillis()))};r?.let {secrets.put("release::${it.id}",it.json())};dao.toggleBookmark(entry)}}
    }
    fun remove(entries:List<LibraryEntry>,bookmarkOnly:Boolean=false) {
        val c=state.value.config;val removed=entries.filter {!bookmarkOnly||it.bookmarked}
        viewModelScope.launch(Dispatchers.IO) {
            for(entry in removed) {
                if(c.historyEnabled) managed.record(c,if(bookmarkOnly) "favorite" else "opening",entry.id,entry.syncJson(),"delete")
                else if(bookmarkOnly) dao.put((dao.entry(entry.id) ?: entry).copy(bookmarked=false))
                else if(entry.bookmarked) dao.put(entry.copy(openedAt=0)) else dao.delete(entry.id)
            }
            if(state.value.config.profileKey==c.profileKey)update {it.copy(removed=removed,removedBookmarks=bookmarkOnly)}
        }
    }
    fun undo(entries:List<LibraryEntry>,bookmarks:Boolean) {
        val c=state.value.config;update {it.copy(removed=null)}
        viewModelScope.launch(Dispatchers.IO) {
            if(state.value.config.profileKey!=c.profileKey)return@launch
            for(entry in entries) if(c.historyEnabled) managed.record(c,if(bookmarks) "favorite" else "opening",entry.id,entry.syncJson(),"restore") else dao.put(entry)
        }
    }
    fun clearUndo() {update {it.copy(removed=null)}}
    fun playRelease(release:Release,season:Int?,onTv:Boolean=false,download:Boolean=false,fromDetailTab:Boolean=false) {
        if(onTv&&(!remote.value.canLaunch||remote.value.profileKey!=state.value.config.profileKey))return
        val origin=if(fromDetailTab) DetailOrigin(state.value.selected,state.value.focusKey) else null
        if(state.value.selected?.id!=release.id)open(release)
        detailOrigin=origin
        update {it.copy(remotePlayback=onTv,downloadSelection=download,releaseSeason=season,releaseEpisode=it.releaseEpisode.takeIf {_->season==it.releaseSeason})}
        prepare()
    }
    fun prepare() {
        if(!canStartPlayback)return
        if(state.value.busy) return
        cancel(); val token=generation; val release=state.value.selected ?: return
        update { it.copy(page="preparing",busy=true,message="Передаём раздачу на сервер…") }
        work=viewModelScope.launch {
            try { val handle=withContext(Dispatchers.IO) { withTimeout(60_000) {
                val gateway=repositories().second
                val previous=state.value.handle
                val known=previous?.hash ?: release.hash.takeUnless {state.value.config.managed}
                var h=if(known==null) gateway.add(release) else try {gateway.status(known)} catch(e:HttpFailure) {if(e.status==404) gateway.add(release) else throw e}
                // Keep the acknowledged handle for safe retries; never add twice after metadata timeout.
                if(token==generation) {update {it.copy(handle=h,selected=if(it.config.managed) it.selected else it.selected?.copy(hash=h.hash),message="Ожидаем список файлов…")}}
                var polls=0
                while(h.files.isEmpty()) { delay(if(polls++<10) 1000 else 2000); h=gateway.status(h.hash) }
                h
            } }
                if(token==generation) update { it.copy(handle=handle,page="files",busy=false,message="") }
            } catch(e:CancellationException) { if(e is TimeoutCancellationException && token==generation) update { it.copy(busy=false,message="Сервер не подготовил файлы за 60 секунд. Повторить или выбрать другую раздачу.") } else throw e
            } catch(e:Exception) { if(token==generation) update { it.copy(busy=false,message=safeError(e)) } }
        }
    }
    fun playerClosed() {
        val origin=detailOrigin
        detailOrigin=null
        update {if(origin==null) it.copy(page="detail") else it.copy(page="detail",selected=origin.release,
            focusKey=origin.focusKey,message="",handle=null,releaseSeason=null,releaseEpisode=null,resumePath=null,
            remotePlayback=false,downloadSelection=false,episodeTargetId=null,episodeDownload=false)}
    }
    fun stream(file:TorrentFile): StreamRequest = repositories().second.stream(state.value.handle!!,file)
    fun check(jackett:Boolean,c:Config,key:String,user:String,password:String) {
        val revision=checkRevision;checkJobs[jackett]?.cancel()
        checkJobs[jackett]=viewModelScope.launch {
            try { val result=withContext(Dispatchers.IO) {
                if(c.demo) { if(jackett) DemoReleases.check() else DemoGateway.check() } else {
                    val c=if(jackett)c else c.copy(torrServer=normalizeDirectAddress(c.torrServer))
                    if(jackett) endpoint(c.jackett) else endpoint(c.torrServer); val scoped=if(jackett) c.copy(torrServer="") else c.copy(jackett="",indexers="",torznabPaths=emptyMap());val client=guardedClient(scoped); val r=TorznabRepository(scoped,key,client)
                    if(jackett) r.check() else TorrServerGateway(scoped,if(user.isBlank()) "" else Credentials.basic(user,password),client,r).check()
                }
            }; if(revision==checkRevision) update { if(jackett) it.copy(checkJackett=result) else it.copy(checkTorr=result) }
            } catch(e:CancellationException) {throw e} catch(e:Exception) { if(revision==checkRevision) update { if(jackett) it.copy(checkJackett=safeError(e)) else it.copy(checkTorr=safeError(e)) } }
        }
    }
    fun invalidateChecks() {checkRevision++;checkJobs.values.forEach {it.cancel()}; update { it.copy(checkJackett="Не проверено",checkTorr="Не проверено") } }
    fun validate(c:Config) {if(c.managed) {require(endpoint(c.managedUrl).scheme=="https");return}; if(!c.demo) { if(c.jackett.isNotBlank()) endpoint(c.jackett); if(c.torrServer.isNotBlank()) endpoint(c.torrServer); require(c.torrServer.isNotBlank()) { "Укажите TorrServer" } } }
    fun save(input:Config,key:String,user:String,password:String,metadataToken:String="",section:String?=null) {
        val current=state.value.config
        val merged=when(section) {
            "search"->current.withSearchSettings(input)
            "server"->current.copy(torrServer=input.torrServer,localHttp=input.localHttp)
            "catalog"->current.copy(metadataProvider=input.metadataProvider)
            else->input
        }
        val c=if(merged.managed)merged else merged.copy(torrServer=runCatching{normalizeDirectAddress(merged.torrServer)}.getOrDefault(merged.torrServer))
        if(state.value.busy) return
        if(dev.mjwatch.playback.PlaybackActivity.hasActivePlayback()) {update {it.copy(message="Остановите воспроизведение перед сменой подключения")};return}
        val target=if(c.managed&&!c.historyEnabled) "managed-login" else if(state.value.page.startsWith("settings-")) "settings" else "home"
        update {it.copy(busy=true,message="")}
        viewModelScope.launch { try {
            android.util.Log.i("MJWatch", "Saving configuration (demo=${c.demo})"); if(section!="search")validate(c);encodeConfig(c)
            withContext(Dispatchers.IO) {
                if(section==null||section=="catalog")secrets.put("tmdb",metadataToken)
                if(section==null||section=="search")secrets.put("jackett:${c.jackett}",key)
                if(section==null||section=="server") {
                    secrets.put("torrAuth:${c.torrServer}",if(user.isBlank()) "" else Credentials.basic(user,password))
                    secrets.put("torrUser:${c.torrServer}",user);secrets.put("torrPassword:${c.torrServer}",password)
                }
            }
            android.util.Log.i("MJWatch", "Credentials saved"); cachedRepositories=null;settings.destination(target);settings.save(c); android.util.Log.i("MJWatch", "Configuration saved"); draftConfig=null; draftKey=""; draftUser=""; draftPassword=""; draftMetadata=""; update { it.copy(page=target,busy=false,message="Настройки сохранены · подключения можно проверить отдельно") }
        } catch(e:Exception) { update { it.copy(busy=false,message=safeError(e)) } } }
    }
    override fun onCleared() { db.close(); super.onCleared() }
}
