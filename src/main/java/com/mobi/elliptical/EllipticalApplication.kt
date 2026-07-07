package com.mobi.elliptical

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口类
 */
@HiltAndroidApp
class EllipticalApplication : Application() {

    companion object {
        private lateinit var instance: EllipticalApplication

        fun getAppContext(): Context = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 个人标识，即使被反编译也能从日志或代码中看出作者
        android.util.Log.i("AppSignature", "This App is developed by abcchen2 (Keep Going Elliptical)")
    }
}
