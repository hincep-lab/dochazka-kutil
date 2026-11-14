package cz.kutil.dochazka

import android.app.Application
import cz.kutil.dochazka.track.MonthlyReminderWorker
import cz.kutil.dochazka.track.Notifier
import cz.kutil.dochazka.track.ZoneHealthWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        ZoneHealthWorker.ensureScheduled(this)
        MonthlyReminderWorker.ensureScheduled(this)
    }
}
