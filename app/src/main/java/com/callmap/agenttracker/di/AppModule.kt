package com.callmap.agenttracker.di

import android.content.Context
import androidx.room.Room
import com.callmap.agenttracker.data.local.AppDatabase
import com.callmap.agenttracker.data.local.dao.CallLogDao
import com.callmap.agenttracker.data.local.dao.DeviceEventDao
import com.callmap.agenttracker.data.local.dao.LocationDao
import com.callmap.agenttracker.data.manager.AppInitializerImpl
import com.callmap.agenttracker.data.manager.EventManagerImpl
import com.callmap.agenttracker.data.manager.ServiceManagerImpl
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
import com.callmap.agenttracker.util.NetworkObserver
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }

    @Provides
    @Singleton
    fun provideAuthApi(gson: Gson): AuthApi {
        return Retrofit.Builder()
            .baseUrl(AuthApi.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    fun provideLocationApi(gson: Gson): LocationApi {
        return Retrofit.Builder()
            .baseUrl(AuthApi.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(LocationApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCallApi(gson: Gson): CallApi {
        return Retrofit.Builder()
            .baseUrl(AuthApi.BASE_URL)
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
    fun provideSyncManager(@ApplicationContext context: Context): SyncManager {
        return SyncManagerImpl(context)
    }

    @Provides
    @Singleton
    fun provideServiceManager(@ApplicationContext context: Context): ServiceManager {
        return ServiceManagerImpl(context)
    }

    @Provides
    @Singleton
    fun provideAppInitializer(impl: AppInitializerImpl): AppInitializer {
        return impl
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
    fun provideLocationRepository(
        api: LocationApi,
        dao: LocationDao,
        sessionManager: SessionManager,
        networkObserver: NetworkObserver
    ): LocationRepository {
        return LocationRepositoryImpl(api, dao, sessionManager, networkObserver)
    }

    @Provides
    @Singleton
    fun provideCallRepository(
        api: CallApi,
        dao: CallLogDao,
        networkObserver: NetworkObserver
    ): CallRepository {
        return CallRepositoryImpl(api, dao, networkObserver)
    }

    @Provides
    @Singleton
    fun provideDeviceEventRepository(impl: DeviceEventRepositoryImpl): DeviceEventRepository {
        return impl
    }

    @Provides
    @Singleton
    fun provideEventManager(impl: EventManagerImpl): EventManager {
        return impl
    }
}
