package com.shadowself.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.shadowself.anomaly.AdaptiveThreshold
import com.shadowself.anomaly.AnomalyRecordStore
import com.shadowself.anomaly.SignalQualityGate
import com.shadowself.anomaly.SlidingWindowBuffer
import javax.inject.Singleton

/**
 * Hilt module for the anomaly detection subsystem.
 * All components are Singleton — one instance for the app lifetime.
 * AnomalyEngine itself is @Inject constructor and auto-bound by Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnomalyModule {
    // All classes in the anomaly package use @Inject constructor + @Singleton,
    // so Hilt auto-provides them. This module exists to document the subsystem
    // boundary and provide any configuration overrides if needed in the future.
}
