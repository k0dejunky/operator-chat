package com.amethyst2213.operatorchat

import android.app.Application

/**
 * The Application the UI tests run with.
 *
 * The real one schedules a WorkManager job on start, and WorkManager cannot
 * initialise itself under Robolectric (it needs a real ContentProvider and
 * a JobScheduler), so the tests use this bare Application instead. The
 * screens under test do not depend on it.
 */
class TestApp : Application()
