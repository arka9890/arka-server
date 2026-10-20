package com.arkacode.app

import android.app.Application

class ArkacodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ArkacodeApp
            private set
    }
}
