package com.capo.diarioclase.data.repository
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.recording.recovery.*
class RoomMarkerStore(private val dao:SessionDao):MarkerStore{
 override suspend fun save(marker:HomeworkMarker){dao.insertMarker(MarkerEntity(marker.id,marker.sessionId.value,marker.blockId.value,marker.absoluteEpochMs,marker.offsetMs,"HOMEWORK",null))}
}
