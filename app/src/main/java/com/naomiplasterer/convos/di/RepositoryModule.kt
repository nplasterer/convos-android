package com.naomiplasterer.convos.di

import com.naomiplasterer.convos.data.api.ConvosApiClient
import com.naomiplasterer.convos.data.api.ConvosApiClientProtocol
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindConvosApiClient(
        impl: ConvosApiClient
    ): ConvosApiClientProtocol
}
