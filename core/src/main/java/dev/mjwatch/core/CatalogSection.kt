package dev.mjwatch.core

enum class CatalogSection(val key:String,val label:String) {
    FOR_YOU("all","Для вас"), MOVIES("movies","Фильмы"), SERIES("series","Сериалы"), ANIME("anime","Аниме"), CARTOONS("cartoons","Мультфильмы");
    fun matches(item:Metadata)=when(this) {
        FOR_YOU->true
        MOVIES->item.kind=="movie"&&!item.animation
        SERIES->item.kind=="tv"&&!item.animation
        ANIME->item.animation&&item.originalLanguage=="ja"
        CARTOONS->item.animation
    }
}
