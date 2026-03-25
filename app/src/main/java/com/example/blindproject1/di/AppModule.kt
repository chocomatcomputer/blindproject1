package com.example.blindproject1.di

import android.content.Context
import com.example.blindproject1.audio.AudioEngine
import com.example.blindproject1.audio.TTSManager
import com.example.blindproject1.audio.VoiceCommandManager
import com.example.blindproject1.haptics.HapticManager
import com.example.blindproject1.ml.ModelDownloader
import com.example.blindproject1.ml.ObjectDetectorHelper
import com.example.blindproject1.network.Esp32GlassesRepository
import com.example.blindproject1.network.TMapRepository
import com.example.blindproject1.sensors.LocationHelper
import com.example.blindproject1.sensors.OrientationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideAudioEngine(): AudioEngine = AudioEngine()

    @Provides
    @Singleton
    fun provideHapticManager(@ApplicationContext context: Context): HapticManager = HapticManager(context)

    @Provides
    @Singleton
    fun provideTTSManager(@ApplicationContext context: Context): TTSManager = TTSManager(context)

    @Provides
    @Singleton
    fun provideVoiceCommandManager(
        @ApplicationContext context: Context,
        ttsManager: TTSManager
    ): VoiceCommandManager = VoiceCommandManager(context, ttsManager)

    @Provides
    @Singleton
    fun provideLocationHelper(@ApplicationContext context: Context): LocationHelper = LocationHelper(context)

    @Provides
    @Singleton
    fun provideOrientationManager(@ApplicationContext context: Context): OrientationManager = OrientationManager(context)

    @Provides
    @Singleton
    fun provideModelDownloader(@ApplicationContext context: Context): ModelDownloader = ModelDownloader(context)

    @Provides
    @Singleton
    fun provideObjectDetectorHelper(modelDownloader: ModelDownloader): ObjectDetectorHelper =
        ObjectDetectorHelper(modelDownloader)

    @Provides
    @Singleton
    fun provideTMapRepository(): TMapRepository = TMapRepository()

    @Provides
    @Singleton
    fun provideEsp32GlassesRepository(
        client: OkHttpClient
    ): Esp32GlassesRepository = Esp32GlassesRepository(client)
}