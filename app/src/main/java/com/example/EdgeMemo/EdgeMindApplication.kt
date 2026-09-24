package com.example.EdgeMemo

import android.app.Application
import com.example.EdgeMemo.di.AppContainer

class EdgeMindApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}