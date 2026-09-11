package com.megane14916.lifetimeline

import android.app.Application
import androidx.work.Configuration
import com.megane14916.lifetimeline.data.AppContainer
import com.megane14916.lifetimeline.data.DefaultAppContainer

class LifeTimelineApplication :
  Application(),
  Configuration.Provider {
  val appContainer: AppContainer by lazy { DefaultAppContainer(this) }

  override val workManagerConfiguration: Configuration
    get() =
      Configuration
        .Builder()
        .setWorkerFactory(appContainer.workerFactory)
        .build()
}
