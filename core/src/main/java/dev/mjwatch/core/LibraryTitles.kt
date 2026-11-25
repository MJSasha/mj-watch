package dev.mjwatch.core

data class LibraryTitle(val entry:LibraryEntry,val metadata:Metadata?,val records:List<LibraryEntry>) {
    val key=entry.metadataId ?: entry.id
    val releaseIds=records.map {it.id}.toSet()
    fun progress(positions:List<Progress>)=positions.filter {it.releaseId in releaseIds}.maxByOrNull {it.updatedAt}
}

/** Retain release IDs for resume/deletion while presenting one title per film/series. */
fun libraryTitles(entries:List<LibraryEntry>,metadata:Map<String,Metadata>):List<LibraryTitle> =
    entries.groupBy {it.metadataId ?: it.id}.map {(id,records)->
        val latest=records.maxBy {it.openedAt}
        val item=metadata[id]
        val canonical=records.firstOrNull {it.id==id} ?: latest
        LibraryTitle(canonical.copy(title=item?.title ?: canonical.title,openedAt=latest.openedAt,
            bookmarked=records.any {it.bookmarked}),item,records)
    }.sortedByDescending {it.entry.openedAt}

/** A section editor cannot replace the current account with an old draft. */
fun Config.withSearchSettings(draft:Config)=copy(jackett=draft.jackett.trim().ifBlank {Config().jackett},
    indexers=draft.indexers,releaseApi=draft.releaseApi,torznabPaths=draft.torznabPaths,localHttp=draft.localHttp,externalReleaseSearch=draft.externalReleaseSearch)

/** Episode identity comes from the saved file, including its season directory. */
fun Progress.episodeLabel():String? {
    val number=episodeNumber(path)
    return number.episode?.let {episode->number.season?.let {"Сезон $it · Серия $episode"} ?: "Серия $episode"}
}

/** Synced progress keeps the torrent identity even when a device has no private release URL. */
fun Progress.resumeRelease(title:String,source:String):Release? {
    val hash=key.substringBefore('|')
    val size=key.substringAfterLast('|').toLongOrNull() ?: return null
    if(!hash.matches(Regex("[a-fA-F0-9]{40}"))||size<0) return null
    if(runCatching {contentKey(hash,path,size)}.getOrNull()!=key) return null
    return Release(releaseId,title,source,hash,magnet="magnet:?xt=urn:btih:$hash")
}
