package com.fsaint.androidagent.communications

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AndroidPhoneNumberNormalizerTest {
    @Test
    fun activeNetworkCountryIsUsedForE164Formatting() {
        val normalizer = AndroidPhoneNumberNormalizer(
            networkCountryIso = { "us" },
            formatter = { number, country -> if (number == "(415) 555-0199" && country == "US") "+14155550199" else null },
        )

        assertEquals("+14155550199", normalizer.normalize("(415) 555-0199"))
    }

    @Test
    fun originalSourceIsRetainedWhenAndroidCannotNormalize() {
        val normalizer = AndroidPhoneNumberNormalizer(
            networkCountryIso = { "" },
            formatter = { _, _ -> error("formatter must not run without a network country") },
        )

        assertEquals("private-number", normalizer.normalize(" private-number "))
    }
}
