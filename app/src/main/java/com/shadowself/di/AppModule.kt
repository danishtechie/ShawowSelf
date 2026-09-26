package com.shadowself.di

import android.content.Context
import com.shadowself.data.local.ShadowSelfDatabase
import com.shadowself.data.repository.SensorDataRepository
import com.shadowself.data.repository.SensorDataRepositoryImpl
import com.shadowself.training.repository.TrainingRepository
import com.shadowself.training.repository.TrainingRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ShadowSelfDatabase =
        androidx.room.Room.databaseBuilder(
            context, ShadowSelfDatabase::class.java, "shadowself.db"
        ).fallbackToDestructiveMigration().build()

    @Provides fun provideSensorWindowDao(db: ShadowSelfDatabase) = db.sensorWindowDao()
    @Provides fun provideIncidentDao(db: ShadowSelfDatabase)     = db.incidentDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindSensorRepository(impl: SensorDataRepositoryImpl): SensorDataRepository

    @Binds @Singleton
    abstract fun bindTrainingRepository(impl: TrainingRepositoryImpl): TrainingRepository
}
