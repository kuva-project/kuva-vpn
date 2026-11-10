package com.kuva.vpn.di

import android.content.Context
import androidx.room.Room
import com.kuva.vpn.data.db.ConnectionHistoryDao
import com.kuva.vpn.data.db.KuvaDatabase
import com.kuva.vpn.data.db.ProfileDao
import com.kuva.vpn.data.db.SubscriptionDao
import com.kuva.vpn.data.repository.ConnectionRepositoryImpl
import com.kuva.vpn.data.repository.ProfileRepositoryImpl
import com.kuva.vpn.domain.repository.ConnectionRepository
import com.kuva.vpn.domain.repository.ProfileRepository
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
    fun provideDatabase(@ApplicationContext ctx: Context): KuvaDatabase =
        Room.databaseBuilder(ctx, KuvaDatabase::class.java, "kuva.db")
            .addMigrations(KuvaDatabase.MIGRATION_1_2, KuvaDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun profileDao(db: KuvaDatabase): ProfileDao = db.profileDao()
    @Provides fun subscriptionDao(db: KuvaDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun historyDao(db: KuvaDatabase): ConnectionHistoryDao = db.connectionHistoryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun profileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds @Singleton
    abstract fun connectionRepository(impl: ConnectionRepositoryImpl): ConnectionRepository
}
