package dev.mjwatch.core

/** Labels are inferred from source titles, never a guarantee about actual tracks. */
private val resolutionPattern=Regex("(?iu)(?<![0-9a-z])(4320p|2160p|1440p|1080[pi]|720p|576p|480p|360p|4k|uhd)(?![0-9a-z])")
private val numberPrefixes=Regex("(?iu)[se]")
private val numberSeparators=Regex("[,;+]\\s*")
private val rangeSeparator=Regex("\\s*[-–]\\s*")
private const val SEASON_NUMBERS="[0-9]{1,2}(?:\\s*(?:[-–,;+]\\s*S?\\s*)[0-9]{1,2})*"
private val seasonPatterns=listOf(
    Regex("(?iu)(?<![a-z0-9])S($SEASON_NUMBERS)(?=E[0-9]|[^a-z0-9]|$)"),
    Regex("(?iu)(?<![0-9])([0-9]{1,2})x[0-9]{1,3}(?![0-9])"))
private val seasonBeforeLabel=Regex("(?iu)(?<![\\p{L}\\p{N}])S?($SEASON_NUMBERS)\\s*$")
private val seasonAfterLabel=Regex("(?iu)^\\s*[: №]*S?($SEASON_NUMBERS)(?![0-9])")
private val seasonLabel=Regex("(?iu)(?<![\\p{L}\\p{N}])(?:сезон(?:ы|ов|а)?|seasons?)(?!\\p{L})")
private val episodeMarker=Regex("(?iu)(?:S[0-9]{1,2}\\s*E|(?<![a-z])E|[0-9]{1,2}x|серия\\s*[: №]*|эпизод\\s*[: №]*|episode\\s*[: №]*)([0-9]{1,4})(?![0-9])")
private val episodePrefix=Regex("^([0-9]{1,3})(?:[ ._-]|$)")

fun releaseResolution(title:String):String {
    val value=resolutionPattern.find(title)?.value?.lowercase()
    return when(value) {"4k","uhd"->"2160p";null->"Не указано";else->value}
}

private fun numberList(raw:String,max:Int):Set<Int> = raw.replace(numberPrefixes,"").split(numberSeparators).flatMap {part->
    val range=part.trim().split(rangeSeparator)
    val first=range.firstOrNull()?.toIntOrNull()
    val last=range.lastOrNull()?.toIntOrNull()
    if(first==null||last==null||first !in 0..max||last !in first..max||last-first>500) emptyList() else (first..last).toList()
}.toSet()

fun seasonNumbers(title:String):Set<Int> {
    val labelled=seasonLabel.findAll(title).flatMap {marker->
        // In "1-3 сезоны: 1-24 серии", the numbers after the label describe episodes.
        val match=seasonBeforeLabel.find(title.substring(0,marker.range.first)) ?: seasonAfterLabel.find(title.substring(marker.range.last+1))
        match?.let {numberList(it.groupValues[1],99)} ?: emptySet()
    }
    return (seasonPatterns.flatMap {pattern->pattern.findAll(title).flatMap {numberList(it.groupValues[1],99)}}+labelled).toSet()
}

data class EpisodeNumber(val season:Int?,val episode:Int?)
fun episodeNumber(path:String):EpisodeNumber {
    val normalized=path.replace('\\','/')
    val name=normalized.substringAfterLast('/').substringBeforeLast('.')
    val season=normalized.split('/').asReversed().firstNotNullOfOrNull {seasonNumbers(it).singleOrNull()}
    val marked=episodeMarker.find(name)?.groupValues?.get(1)?.toIntOrNull()
    val plain=episodePrefix.find(name)?.groupValues?.get(1)?.toIntOrNull()
    return EpisodeNumber(season,marked ?: plain)
}

private val voicePatterns=listOf(
        "LostFilm" to "lost[ ._-]?film", "NewStudio" to "new[ ._-]?studio",
        "HDrezka" to "hdrezka(?:[ ._-]+studio)?", "Кубик в Кубе" to "кубик[ ._-]+в[ ._-]+кубе",
        "Кураж-Бамбей" to "кураж[ ._-]*бамбей", "Кинопоиск" to "кинопоиск",
        "AlexFilm" to "alex[ ._-]?film", "RuDub" to "rudub", "AniLibria" to "anilibria",
        "AniDub" to "anidub", "Дубляж" to "дубляж|дублированн[а-я]*|dub",
        "Многоголосая" to "многоголос[а-я]*|mvo", "Двухголосая" to "двухголос[а-я]*|dvo",
        "Одноголосая" to "одноголос[а-я]*|vo", "Оригинал" to "оригинал|original")
    .map {(label,pattern)->label to Regex("(?iu)(?<![a-zа-яё0-9])(?:$pattern)(?![a-zа-яё0-9])")}

fun releaseVoices(title:String):Set<String> = voicePatterns.mapNotNullTo(linkedSetOf()) {(label,pattern)->
    label.takeIf {pattern.containsMatchIn(title)}
}

data class ReleaseFilter(val quality:String="Все",val season:Int?=null,val voice:String?=null) {
    fun matches(release:Release):Boolean = (quality=="Все"||release.resolution==quality)&&
        (season==null||season in seasonNumbers(release.title))&&(voice==null||voice in releaseVoices(release.title))
}

fun sortedVideoFiles(files:List<TorrentFile>):List<TorrentFile> = files
    .map {Triple(it,episodeNumber(it.path),it.path.lowercase())}
    .sortedWith(compareBy<Triple<TorrentFile,EpisodeNumber,String>> {it.second.season ?: Int.MAX_VALUE}
        .thenBy {it.second.episode ?: Int.MAX_VALUE}.thenBy {it.third})
    .map {it.first}
