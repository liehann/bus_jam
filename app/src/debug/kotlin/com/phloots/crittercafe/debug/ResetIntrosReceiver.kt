package com.phloots.crittercafe.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phloots.crittercafe.data.SeenMechanicsStore
import com.phloots.crittercafe.data.progressDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only: `adb shell am broadcast -a com.phloots.crittercafe.RESET_INTROS -p com.phloots.crittercafe` forgets the
 * mechanic intros already shown, so they show again (the next time a level is opened).
 */
class ResetIntrosReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SeenMechanicsStore(context.applicationContext.progressDataStore).reset()
                Log.i("CritterCafe", "mechanic intros reset")
            } finally {
                pending.finish()
            }
        }
    }
}
