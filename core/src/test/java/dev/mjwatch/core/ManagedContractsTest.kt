package dev.mjwatch.core
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManagedContractsTest {
 @Test fun oldConfigDefaultsToDirectAndExportsNeverTransferAccountTrust(){
  val old=decodeConfig("{\"schemaVersion\":1,\"torrServer\":\"http://192.168.1.2:8090\"}")
  assertEquals("direct",old.connectionMode);assertFalse(old.historyEnabled)
  val managed=old.copy(connectionMode="managed",managedUrl="https://server.example:8443",serverId="server",userId="user")
  assertTrue(managed.historyEnabled);val export=JSONObject(encodeConfig(managed,includeAccount=false));assertEquals("",export.getString("serverId"));assertEquals("",export.getString("userId"));assertFalse(export.toString().contains("accessToken"));assertFalse(export.toString().contains("sshPassword"))
  assertFalse(managed.copy(userId="").historyEnabled);assertFalse(Config(demo=true).historyEnabled)
 }
 @Test fun directAddressNormalizationPreservesExplicitUrlsAndPorts(){
  assertEquals("http://192.168.1.2:8090",normalizeDirectAddress("192.168.1.2"));assertEquals("http://192.168.1.2:80",normalizeDirectAddress("192.168.1.2:80"))
  assertEquals("https://host.test/reverse/proxy/",normalizeDirectAddress("https://host.test/reverse/proxy/"));assertEquals("http://[::1]:8090",normalizeDirectAddress("::1"))
  for(raw in listOf("127.0.0.1?key=secret","user:password@127.0.0.1","file:///tmp/movie")){assertTrue(runCatching{endpoint(normalizeDirectAddress(raw))}.isFailure)}
 }
 @Test fun contentIdentityIncludesHashNormalizedPathAndSize(){
  val hash="0123456789abcdef0123456789abcdef01234567"
  assertEquals(contentKey(hash,"Season/episode.mkv",123),contentKey(hash.uppercase(),"Season\\episode.mkv",123))
  assertNotEquals(contentKey(hash,"a/episode.mkv",123),contentKey(hash,"b/episode.mkv",123));assertNotEquals(contentKey(hash,"a.mkv",123),contentKey(hash,"a.mkv",124));assertTrue(runCatching{contentKey(hash,"../movie.mp4",1)}.isFailure)
 }
 @Test fun scopedOutboxOrderNeverUsesDeviceClockAndPullCannotOverwritePending()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),WatchDatabase::class.java).build()
  try {val dao=db.syncDao();dao.put(SyncCursor("A","epoch",1));dao.put(SyncCursor("B","epoch",1))
   val first=OutboxEntry("first","A","progress","key","{\"positionMs\":5000}",0,"put","epoch",1,created=9999)
   val second=first.copy(id="second",json="{\"positionMs\":2000}",baseRevision=1,created=1)
   dao.put(first);dao.put(second);assertEquals(listOf("first","second"),dao.pending("A").map{it.id});assertTrue(dao.pending("B").isEmpty())
   dao.put(SyncEntity("A","progress","key",second.json,0));dao.put(SyncEntity("B","progress","key","{\"positionMs\":8000}",2))
   dao.pull("A",listOf(SyncEntity("A","progress","key","{\"positionMs\":9000}",9)),SyncCursor("A","epoch",1,10),false)
   assertEquals(2000,JSONObject(dao.entity("A","progress","key")!!.json).getInt("positionMs"));assertEquals(8000,JSONObject(dao.entity("B","progress","key")!!.json).getInt("positionMs"));assertEquals(10L,dao.cursor("A")!!.cursor)
   dao.pull("A",emptyList(),SyncCursor("A","new-epoch",2,11),true);assertEquals(second.json,dao.entity("A","progress","key")!!.json);assertNotNull(dao.entity("B","progress","key"))
  }finally{db.close()}
 }
 @Test fun scopedMetadataDoesNotReuseAnotherProfilesImageOrigin()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),WatchDatabase::class.java).build()
  try{val item=Metadata("tmdb:movie:1","Fixture","https://a.example/image.jpg",null);db.dao().cacheMetadata("A:home",listOf(item),1,"A");db.dao().cacheMetadata("B:home",listOf(item.copy(poster="https://b.example/image.jpg")),1,"B");assertEquals(item,db.dao().cachedMetadata("A:home").single());assertEquals("https://b.example/image.jpg",db.dao().cachedMetadata("B:home").single().poster)}finally{db.close()}
 }
 @Test fun deliberateNewViewingRestoresATombstoneAgainstCurrentEpoch()=runBlocking {
  val context=RuntimeEnvironment.getApplication()
  val db=Room.inMemoryDatabaseBuilder(context,WatchDatabase::class.java).build()
  try {
   val c=Config(connectionMode="managed",managedUrl="https://fixture.invalid",serverId="server",userId="user");val dao=db.syncDao()
   dao.put(SyncCursor(c.scope,"epoch",7));dao.put(SyncEntity(c.scope,"progress","key","{}",5,true))
   SyncEngine(ManagedApi(c,SecretStore(context)),dao).enqueue("progress","key",JSONObject().put("positionMs",1000))
   val queued=dao.pending(c.scope).single();assertEquals("restore",queued.operation);assertEquals(5L,queued.baseRevision);assertEquals(7L,queued.historyEpoch)
  }finally{db.close()}
 }

 @Test fun slowMutationDoesNotBlockLocalPlaybackWritesOrReplaceTheirPosition()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),WatchDatabase::class.java).build()
  try {
   val dao=db.syncDao()
   val scope="slow-server:viewer"
   val cursor=SyncCursor(scope,"epoch",1)
   dao.put(cursor)
   val awaitingReceipt=CompletableDeferred<Unit>()
   val sendReceipt=CompletableDeferred<Unit>()
   val engine=SyncEngine(scope,dao) {path,_,body->
    if(path=="mutations") {
     val mutation=checkNotNull(body)
     if(mutation.getString("kind")=="progress") {
      awaitingReceipt.complete(Unit)
      sendReceipt.await()
     }
     JSONObject().put("entity",JSONObject()
      .put("kind",mutation.getString("kind")).put("entityKey",mutation.getString("entityKey"))
      .put("value",mutation.getJSONObject("value")).put("revision",1))
    } else {
     assertTrue(path.startsWith("sync?"))
     JSONObject("""{"serverEpoch":"epoch","historyEpoch":1,"cursor":2,"entities":[]}""")
    }
   }
   val opening=LibraryEntry("release","Сериал","fixture",openedAt=1)
   val progress=Progress("episode","release",1,"S02E06.mkv",20_000,90_000,2,false)
   engine.recordPlayback(opening,progress,"session",1)
   val syncing=async {engine.sync(JSONObject("""{"serverEpoch":"epoch","historyEpoch":1}"""))}
   try {
    withTimeout(5_000) {
     awaitingReceipt.await()
     val newer=progress.copy(positionMs=40_000,updatedAt=3)
     engine.recordPlayback(opening,newer,"session",2)
     assertEquals(newer,dao.resumeProgress(scope,opening.id,progress.key))
    }
   } finally {
    sendReceipt.complete(Unit)
   }
   syncing.await()
   assertEquals(40_000L,dao.resumeProgress(scope,opening.id,progress.key)?.positionMs)
   assertEquals(2L,dao.pending(scope).single().sequence)
   assertEquals(opening,dao.entity(scope,"opening",opening.id)?.libraryEntry())
  } finally {db.close()}
 }

 @Test fun playbackBeforeFirstSnapshotSurvivesAndOlderEpochRequiresResolution()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),WatchDatabase::class.java).build()
  try {
   val dao=db.syncDao()
   val scope="offline-server:viewer"
   val engine=SyncEngine(scope,dao) {_,_,_->error("Recording must not use the network")}
   val opening=LibraryEntry("release","Сериал","fixture",openedAt=1)
   val progress=Progress("episode","release",1,"S02E06.mkv",20_000,90_000,2,false)
   val newer=progress.copy(positionMs=40_000,updatedAt=3)
   engine.recordPlayback(opening,progress,"session",1)
   engine.recordPlayback(opening,newer,"session",2)
   assertEquals(newer,dao.resumeProgress(scope,opening.id,progress.key))
   assertNull(dao.cursor(scope))
   assertEquals(3,dao.pending(scope).size)

   val older=SyncEntity(scope,"progress",progress.key,progress.syncJson().toString(),4)
   dao.applySnapshot(scope,listOf(older),SyncCursor(scope,"epoch",1,4))
   val queued=dao.pending(scope)
   val pendingProgress=queued.single {it.kind=="progress"}
   assertEquals(2,queued.size)
   assertEquals(4L,pendingProgress.baseRevision)
   assertEquals(2L,pendingProgress.sequence)
   assertEquals(newer,dao.resumeProgress(scope,opening.id,progress.key))

   val tombstone=older.copy(json="{}",revision=5,deleted=true)
   val cleared=SyncCursor(scope,"epoch",2,5)
   dao.applySnapshot(scope,listOf(tombstone),cleared)
   assertTrue(dao.pending(scope).all {it.conflict.isNotBlank()&&it.historyEpoch==1L})
   val other=SyncEntity(scope,"opening","other",opening.copy(id="other").syncJson().toString(),1)
   dao.resolveSnapshot(pendingProgress,listOf(tombstone,other),cleared.copy(cursor=9),useLocal=false)
   assertNull(dao.resumeProgress(scope,opening.id,progress.key))
   assertEquals(other,dao.entity(scope,"opening","other"))
   assertEquals(9L,dao.cursor(scope)?.cursor)
  } finally {db.close()}
 }

 @Test fun failedSyncBacksOffAndHealthySyncResetsImmediately(){
  assertEquals(listOf(30000L,60000L,120000L,240000L,300000L,300000L),(0..5).map(::syncPauseMillis))
  assertEquals(300000L,syncPauseMillis(Int.MAX_VALUE))
  assertEquals(30000L,syncPauseMillis(0))
 }

}
