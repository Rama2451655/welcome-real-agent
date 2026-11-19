package com.rama.riya

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse

/**
 * Android hands the incoming number to this service BEFORE the phone starts ringing, so Riya can say the
 * caller's name with practically no delay (the normal phone-state broadcast can arrive a second later and
 * often without the number). It never blocks, silences or changes a call: every call is allowed through.
 */
class CallScreener : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        try {
            val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
            if (incoming) RiyaService.instance?.onScreenedNumber(details.handle?.schemeSpecificPart)
        } catch (e: Throwable) { }
        try { respondToCall(details, CallResponse.Builder().build()) } catch (e: Throwable) { }
    }
}
