package com.callmap.agenttracker.domain.repository

import com.callmap.agenttracker.data.remote.dto.DeviceRegistrationRequest
import com.callmap.agenttracker.domain.model.RegistrationResult
import com.callmap.agenttracker.util.Resource

interface AuthRepository {
    suspend fun registerDevice(request: DeviceRegistrationRequest): Resource<RegistrationResult>
}
