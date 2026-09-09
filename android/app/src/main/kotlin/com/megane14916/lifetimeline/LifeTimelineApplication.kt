package com.megane14916.lifetimeline

import android.app.Application
import com.megane14916.lifetimeline.data.AppContainer
import com.megane14916.lifetimeline.data.DefaultAppContainer

class LifeTimelineApplication : Application() {
  val appContainer: AppContainer by lazy { DefaultAppContainer() }
}
