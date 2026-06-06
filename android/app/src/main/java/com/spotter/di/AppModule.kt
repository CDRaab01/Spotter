package com.spotter.di

import android.content.Context
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.spotter.BuildConfig
import com.spotter.data.local.SpotterDatabase
import com.spotter.data.local.SpotterDatabase.Companion.MIGRATION_2_3
import com.spotter.data.local.SpotterDatabase.Companion.MIGRATION_3_4
import com.spotter.data.remote.ApiService
import com.spotter.data.remote.AuthInterceptor
import com.spotter.data.remote.HostSelectionInterceptor
import com.spotter.data.remote.TokenRefreshAuthenticator
import com.spotter.util.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {


    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext ctx: Context): TokenStore = TokenStore(ctx)

    @Provides
    @Singleton
    fun provideOkHttp(
        authInterceptor: AuthInterceptor,
        hostSelectionInterceptor: HostSelectionInterceptor,
        tokenRefreshAuthenticator: TokenRefreshAuthenticator,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .authenticator(tokenRefreshAuthenticator)
            // AI chat proxies to a local LLM; the first request triggers a cold model
            // load + inference that can take well over OkHttp's 10s default read timeout,
            // which surfaced to users as a "timeout" during initial setup. Plan/program
            // generation runs on the larger, slower model (server LM_STUDIO_PLAN_TIMEOUT,
            // default 180s). Keep the read window comfortably above that so the server's
            // meaningful error (502/503/504) reaches the client instead of a socket timeout.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(210, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(hostSelectionInterceptor)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(json: Json, okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): SpotterDatabase =
        Room.databaseBuilder(ctx, SpotterDatabase::class.java, "spotter.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            // Safety net for schema versions that predate MIGRATION_2_3 (i.e. v1).
            // Room is a server mirror only — no data is user-originated, so clearing
            // and re-syncing is safe.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideChatMessageDao(db: SpotterDatabase) = db.chatMessageDao()
}
