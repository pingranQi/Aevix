package org.aevix.impl.hooks

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AevixChainTest {
    private class Receiver {
        fun target() = Unit
    }

    private class Unrelated

    @Test
    fun proceedWithRejectsReceiverOutsideDeclaringClass() {
        val executable: Method = Receiver::class.java.getDeclaredMethod("target")
        val chain =
            AevixChain(
                executable = executable,
                thisObj = null,
                args = emptyArray(),
                hooks = emptyArray(),
                hookIndex = 0,
                terminal = { receiver, _ -> receiver },
            )

        assertThrows(IllegalArgumentException::class.java) {
            chain.proceedWith(Unrelated())
        }
    }

    @Test
    fun proceedWithKeepsTheOriginalExecutableAndUsesCompatibleReceiver() {
        val executable: Method = Receiver::class.java.getDeclaredMethod("target")
        val receiver = Receiver()
        val chain =
            AevixChain(
                executable = executable,
                thisObj = null,
                args = emptyArray(),
                hooks = emptyArray(),
                hookIndex = 0,
                terminal = { actualReceiver, _ -> actualReceiver },
            )

        assertEquals(receiver, chain.proceedWith(receiver))
    }
}
