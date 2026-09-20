package net.raphaelgf11.ilo3manager.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the scheduler's own alarm; all the decisions live in [PanelRefreshScheduler]. */
class PanelTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PanelRefreshScheduler.onTick(context)
    }
}
