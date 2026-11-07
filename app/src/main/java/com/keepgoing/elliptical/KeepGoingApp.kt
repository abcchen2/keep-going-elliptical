package com.keepgoing.elliptical

import android.app.Application

class KeepGoingApp : Application() {
    lateinit var controller: EllipticalController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = EllipticalController(this)
    }
}
