package com.callmap.agenttracker.domain.repository

import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.callmap.agenttracker.util.Resource
import kotlinx.coroutines.flow.Flow

interface LocationRepository {
    suspend fun saveLocation(location: LocationEntity)
    /** Moves file spillover queue into Room when possible. */
    suspend fun importSpilloverLocations(): Int
    suspend fun syncPendingLocations(): Resource<Int>
    suspend fun uploadSingleLocation(location: LocationEntity): Resource<Unit>
}
