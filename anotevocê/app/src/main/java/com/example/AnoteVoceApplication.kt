package com.example

import android.app.Application
import com.example.core.di.AppContainer

class AnoteVoceApplication : Application() {
  val container: AppContainer by lazy { AppContainer(this) }
}
