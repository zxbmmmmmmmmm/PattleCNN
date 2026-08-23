package org.bettafish.huelab

import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNull

class ShaderRuntimeTest {
    @Test
    fun builtInShaderMatchesContractAndCompilesOnDesktop() {
        assertNull(validateShaderSource(DEFAULT_SHADER_SOURCE))
        RuntimeEffect.makeForShader(DEFAULT_SHADER_SOURCE).close()
    }

    @Test
    fun invalidShaderIsRejected() {
        assertFails { RuntimeEffect.makeForShader("half4 main(float2 p) { broken }") }
    }
}
