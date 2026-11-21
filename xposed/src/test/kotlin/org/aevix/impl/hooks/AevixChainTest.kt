package org.aevix.impl.hooks

import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AevixChainTest {

    open class Parent {
        fun target() {}
    }

    class Child : Parent()

    class Unrelated

    object StaticTarget {
        @JvmStatic fun target() {}
    }

    @Test
    fun proceedWithRejectsAnUnrelatedReceiverBeforeRunningDownstreamHooks() {
        val executable = Parent::class.java.getDeclaredMethod("target")
        var downstreamCalled = false
        val record =
            AevixHookRecord(
                Hooker { chain -> chain.proceedWith(Unrelated()) },
                50,
                ExceptionMode.PASSTHROUGH,
                null,
            )
        val chain =
            AevixChain(
                executable,
                Parent(),
                emptyArray(),
                arrayOf(record),
                0,
            ) { _, _ ->
                downstreamCalled = true
                null
            }

        try {
            chain.proceed()
            fail("Expected proceedWith to reject an unrelated receiver")
        } catch (_: IllegalArgumentException) {
            // Expected: validation must happen before the terminal is entered.
        }

        assertFalse(downstreamCalled)
    }

    @Test
    fun proceedWithAcceptsASubclassWithoutChangingTheExecutable() {
        val executable = Parent::class.java.getDeclaredMethod("target")
        val receiver = Child()
        var terminalReceiver: Any? = null
        val chain =
            AevixChain(executable, Parent(), emptyArray(), emptyArray(), 0) { thisObject, _ ->
                terminalReceiver = thisObject
                null
            }

        chain.proceedWith(receiver)

        assertSame(receiver, terminalReceiver)
        assertSame(executable, chain.getExecutable())
    }

    @Test
    fun proceedWithRejectsStaticMethods() {
        val executable = StaticTarget::class.java.getDeclaredMethod("target")
        var terminalCalled = false
        val chain =
            AevixChain(executable, null, emptyArray(), emptyArray(), 0) { _, _ ->
                terminalCalled = true
                null
            }

        try {
            chain.proceedWith(StaticTarget)
            fail("Expected proceedWith to reject a static executable")
        } catch (_: IllegalArgumentException) {
            // Expected: static methods have no receiver.
        }

        assertFalse(terminalCalled)
    }
}
