package com.fsaint.androidagent.communications

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

class AndroidPhoneNumberNormalizer internal constructor(
    private val networkCountryIso: () -> String?,
    private val formatter: (String, String) -> String?,
) : PhoneNumberNormalizer {
    constructor(context: Context) : this(
        networkCountryIso = { context.getSystemService(TelephonyManager::class.java)?.networkCountryIso },
        formatter = PhoneNumberUtils::formatNumberToE164,
    )

    override fun normalize(source: String): String {
        val original = source.trim()
        val country = networkCountryIso()?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.US)
            ?: return original
        return runCatching { formatter(original, country) }.getOrNull() ?: original
    }
}
