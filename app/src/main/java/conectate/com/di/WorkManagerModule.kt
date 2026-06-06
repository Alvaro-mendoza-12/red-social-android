@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {
    @Provides
    fun provideHiltWorkerFactory(appContext: Application): HiltWorkerFactory {
        return HiltWorkerFactory(appContext)
    }
}
