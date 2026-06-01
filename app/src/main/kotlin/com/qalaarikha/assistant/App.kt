package com.qalaarikha.assistant

import android.app.Application
import com.qalaarikha.assistant.data.preferences.PreferencesManager
import com.qalaarikha.assistant.data.repository.ContactRepository
import com.qalaarikha.assistant.data.repository.ContactRepositoryImpl

/**
 * Application entry-point.
 * Serves as the manual-DI root: creates long-lived singletons once
 * so that every ViewModel can share the same instances.
 */
class App : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var contactRepository: ContactRepository
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager   = PreferencesManager(this)
        contactRepository    = ContactRepositoryImpl(this, preferencesManager)
    }
}
