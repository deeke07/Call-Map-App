package com.callmap.agenttracker.di

import android.content.Context
import androidx.room.Room
import com.callmap.agenttracker.data.local.AppDatabase
import com.callmap.agenttracker.data.local.dao.CallLogDao
import com.callmap.agenttracker.data.local.dao.DeviceEventDao
import com.callmap.agenttracker.data.local.dao.LocationDao
import com.callmap.agenttracker.data.manager.AlarmScheduler
import com.callmap.agenttracker.data.manager.AppInitializerImpl
import com.callmap.agenttracker.data.manager.DataCleanupManager
import com.callmap.agenttracker.data.manager.DeviceRestartDetector
import com.callmap.agenttracker.data.manager.EventManagerImpl
import com.callmap.agenttracker.data.manager.ServiceManagerImpl
import com.callmap.agenttracker.data.manager.ServiceRestartManager
import com.callmap.agenttracker.data.manager.SessionManagerImpl
import com.callmap.agenttracker.data.manager.SyncManagerImpl
import com.callmap.agenttracker.data.remote.api.AuthApi
import com.callmap.agenttracker.data.remote.api.CallApi
import com.callmap.agenttracker.data.remote.api.LocationApi
import com.callmap.agenttracker.data.repository.AuthRepositoryImpl
import com.callmap.agenttracker.data.repository.CallRepositoryImpl
import com.callmap.agenttracker.data.repository.DeviceEventRepositoryImpl
import com.callmap.agenttracker.data.repository.LocationRepositoryImpl
import com.callmap.agenttracker.domain.manager.AppInitializer
import com.callmap.agenttracker.domain.manager.EventManager
import com.callmap.agenttracker.domain.manager.ServiceManager
import com.callmap.agenttracker.domain.manager.SessionManager
import com.callmap.agenttracker.domain.manager.SyncManager
import com.callmap.agenttracker.domain.repository.AuthRepository
import com.callmap.agenttracker.domain.repository.CallRepository
import com.callmap.agenttracker.domain.repository.DeviceEventRepository
import com.callmap.agenttracker.domain.repository.LocationRepository
import com.callmap.agenttracker.domain.usecase.location.ShouldTrackLocationUseCase
import com.callmap.agenttracker.domain.usecase.location.NextTriggerTimeCalculator
import com.callmap.agenttracker.util.NetworkObserver
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }

    @Provides
    @Singleton
    fun provideAuthApi(gson: Gson, okHttpClient: OkHttpClient): AuthApi {
        return Retrofit.Builder()
            .baseUrl(AuthApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    fun provideLocationApi(gson: Gson, okHttpClient: OkHttpClient): LocationApi {
        return Retrofit.Builder()
            .baseUrl(AuthApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(LocationApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCallApi(gson: Gson, okHttpClient: OkHttpClient): CallApi {
        return Retrofit.Builder()
            .baseUrl(AuthApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(CallApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideLocationDao(db: AppDatabase): LocationDao {
        return db.locationDao()
    }

    @Provides
    @Singleton
    fun provideCallLogDao(db: AppDatabase): CallLogDao {
        return db.callLogDao()
    }

    @Provides
    @Singleton
    fun provideDeviceEventDao(db: AppDatabase): DeviceEventDao {
        return db.deviceEventDao()
    }

    @Provides
    @Singleton
    fun provideSessionManager(@ApplicationContext context: Context): SessionManager {
        return SessionManagerImpl(context)
    }

    @Provides
    @Singleton
    fun provideSyncManager(
        @ApplicationContext context: Context,
        sessionManager: SessionManager,
        nextTriggerCalculator: NextTriggerTimeCalculator,
        alarmScheduler: AlarmScheduler
    ): SyncManager {
        return SyncManagerImpl(context, sessionManager, nextTriggerCalculator, alarmScheduler)
    }

    @Provides
    @Singleton
    fun provideServiceManager(@ApplicationContext context: Context): ServiceManager {
        return ServiceManagerImpl(context)
    }

    @Provides
    @Singleton
    fun provideAppInitializer(
        @ApplicationContext context: Context,
        syncManager: SyncManager,
        serviceManager: ServiceManager,
        sessionManager: SessionManager,
        callRepository: CallRepository,
        shouldTrackLocationUseCase: ShouldTrackLocationUseCase
    ): AppInitializer {
        return AppInitializerImpl(context, syncManager, serviceManager, sessionManager, callRepository, shouldTrackLocationUseCase)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        api: AuthApi,
        sessionManager: SessionManager,
        gson: Gson
    ): AuthRepository {
        return AuthRepositoryImpl(api, sessionManager, gson)
    }

    @Provides
    @Singleton
    fun provideDataCleanupManager(
        @ApplicationContext context: Context,
        locationDao: LocationDao,
        callLogDao: CallLogDao
    ): DataCleanupManager {
        return DataCleanupManager(context, locationDao, callLogDao)
    }

    @Provides
    @Singleton
    fun provideDeviceRestartDetector(@ApplicationContext context: Context): DeviceRestartDetector {
        return DeviceRestartDetector(context)
    }

    @Provides
    @Singleton
    fun provideServiceRestartManager(@ApplicationContext context: Context): ServiceRestartManager {
        return ServiceRestartManager(context)
    }

    @Provides
    @Singleton
    fun provideLocationRepository(
        api: LocationApi,
        dao: LocationDao,
        sessionManager: SessionManager,
        networkObserver: NetworkObserver,
        cleanupManager: DataCleanupManager
    ): LocationRepository {
        return LocationRepositoryImpl(api, dao, sessionManager, networkObserver, cleanupManager)
    }

    @Provides
    @Singleton
    fun provideCallRepository(
        api: CallApi,
        dao: CallLogDao,
        networkObserver: NetworkObserver,
        cleanupManager: DataCleanupManager
    ): CallRepository {
        return CallRepositoryImpl(api, dao, networkObserver, cleanupManager)
    }

    @Provides
    @Singleton
    fun provideDeviceEventRepository(
        @ApplicationContext context: Context,
        dao: DeviceEventDao,
        api: CallApi,
        sessionManager: SessionManager,
        networkObserver: NetworkObserver,
        gson: Gson
    ): DeviceEventRepository {
        return DeviceEventRepositoryImpl(context, dao, api, sessionManager, networkObserver, gson)
    }

    @Provides
    @Singleton
    fun provideEventManager(repository: DeviceEventRepository): EventManager {
        return EventManagerImpl(repository)
    }
}
