package dev.mjwatch.playback

import dev.mjwatch.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
 @Test fun transientHttpAndExpiredManagedSessionsRecoverButDenialsAndFormatsDoNot() {
  for(status in listOf(408,429,500,502,503,504,404))assertTrue(recoverablePlaybackError(2004,status,false))
  assertTrue(recoverablePlaybackError(2004,401,true))
  assertFalse(recoverablePlaybackError(2004,401,false));assertFalse(recoverablePlaybackError(2004,403,true))
  assertTrue(recoverablePlaybackError(2002,null,true))
  assertFalse(recoverablePlaybackError(2007,null,true));assertFalse(recoverablePlaybackError(4001,null,true))
 }
 @Test fun missingTorrentIsReaddedAndExactFileIdentityIsRequired()=runBlocking {
  val release=Release("r","title","source",magnet="original-private-source")
  var adds=0
  var handle=TorrentHandle("new-handle",listOf(TorrentFile(7,"season/episode.mkv",100)),"abc")
  val gateway=object:StreamGateway {
   override suspend fun check()="ok"
   override suspend fun status(hash:String):TorrentHandle=throw HttpFailure(404)
   override suspend fun add(value:Release):TorrentHandle {assertEquals(release,value);adds++;return handle}
   override fun stream(handle:TorrentHandle,file:TorrentFile)=StreamRequest("unused")
  }
  assertEquals(7,recoverTorrent(gateway,release,"old-handle","abc","season/episode.mkv").files.single().serverFileId)
  assertEquals(1,adds)
  handle=handle.copy(contentHash="other")
  assertTrue(runCatching {recoverTorrent(gateway,release,"old-handle","abc","season/episode.mkv")}.exceptionOrNull() is IllegalStateException)
  handle=handle.copy(contentHash="abc",files=listOf(TorrentFile(1,"other/episode.mkv",100)))
  assertTrue(runCatching {recoverTorrent(gateway,release,"old-handle","abc","season/episode.mkv")}.exceptionOrNull() is IllegalStateException)
 }
}
