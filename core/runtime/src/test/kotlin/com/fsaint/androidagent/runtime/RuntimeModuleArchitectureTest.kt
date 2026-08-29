package com.fsaint.androidagent.runtime

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class RuntimeModuleArchitectureTest {
    @Test
    fun `runtime test classpath excludes Android UI types`() {
        val classLoader = javaClass.classLoader

        listOf(
            "android.app.Service",
            "androidx.compose.runtime.Composer",
            "androidx.compose.ui.Modifier",
        ).forEach { forbiddenType ->
            assertFailsWith<ClassNotFoundException>(forbiddenType) {
                classLoader.loadClass(forbiddenType)
            }
        }
    }
}
