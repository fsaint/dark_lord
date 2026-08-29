package com.fsaint.androidagent.runtime

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RuntimeModuleArchitectureTest {
    @Test
    fun `runtime module declares no Android UI dependencies`() {
        assertEquals(emptySet<String>(), RuntimeModuleArchitecture.androidUiDependencies)
    }
}
