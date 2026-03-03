package conectate.com.di

import conectate.com.core.concurrency.DefaultDispatcherProvider
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.crash.AndroidCrashReporter
import conectate.com.core.crash.CrashReporter
import conectate.com.core.feed.DefaultFeedRankingEngine
import conectate.com.core.feed.FeedRankingEngine
import conectate.com.core.feed.FeedSignalTracker
import conectate.com.core.feed.InMemoryFeedSignalTracker
import conectate.com.core.logging.AndroidAppLogger
import conectate.com.core.logging.AppLogger
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.SimpleContentModerationService
import conectate.com.core.observability.AppMetrics
import conectate.com.core.observability.InMemoryAppMetrics
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.core.telemetry.InMemoryTelemetry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindAppLogger(impl: AndroidAppLogger): AppLogger

    @Binds
    @Singleton
    abstract fun bindCrashReporter(impl: AndroidCrashReporter): CrashReporter

    @Binds
    @Singleton
    abstract fun bindTelemetry(impl: InMemoryTelemetry): AppTelemetry

    @Binds
    @Singleton
    abstract fun bindFeedRankingEngine(impl: DefaultFeedRankingEngine): FeedRankingEngine

    @Binds
    @Singleton
    abstract fun bindFeedSignalTracker(impl: InMemoryFeedSignalTracker): FeedSignalTracker

    @Binds
    @Singleton
    abstract fun bindContentModerationService(impl: SimpleContentModerationService): ContentModerationService

    @Binds
    @Singleton
    abstract fun bindAppMetrics(impl: InMemoryAppMetrics): AppMetrics
}
