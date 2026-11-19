package com.example.batterypercent

import android.app.Activity
import java.lang.ref.WeakReference

/** Shared state between the service and the clock screen (they live in one process). */
object ClockState {
    /** The clock is alive: it is "standing in" for the switched-off screen. */
    @Volatile var showing = false

    /**
     * The current time is outside the schedule: the clock only shows black and no longer keeps the screen on,
     * so the screen switches off by itself after the normal timeout (and that is not a power-button press).
     */
    @Volatile var expired = false

    private var activity: WeakReference<Activity>? = null

    fun attach(a: Activity) {
        activity = WeakReference(a)
        showing = true
        expired = false
    }

    fun detach(a: Activity) {
        if (activity?.get() === a) activity = null
        showing = false
        expired = false
    }

    /** Closes the clock (main thread only). */
    fun closeScreen() {
        showing = false
        activity?.get()?.finishAndRemoveTask()
    }
}
