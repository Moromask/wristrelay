package com.wristrelay.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context

class MergeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: MergeApplication
            private set

        val appContext: Context
            get() = instance.applicationContext
    }
}
