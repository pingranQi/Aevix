package org.aevix.daemon.data

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferenceStoreSerializationTest {
  private data class UnsupportedPreference(val value: String) : java.io.Serializable

  @Test
  fun supportedPreferenceValuesRoundTrip() {
    val values =
        listOf(
            "value",
            true,
            42,
            9L,
            1.5f,
            emptySet<String>(),
            setOf("one"),
            setOf("one", "two"),
        )
    values.forEach { value ->
      assertEquals(value, deserializePreferenceValue(serializePreferenceValue(value)))
    }
  }

  @Test
  fun rejectsApplicationDefinedSerializableClasses() {
    val bytes =
        ByteArrayOutputStream().also { output ->
          ObjectOutputStream(output).use { it.writeObject(UnsupportedPreference("payload")) }
        }.toByteArray()

    assertFalse(isSupportedPreferenceValue(UnsupportedPreference("payload")))
    try {
      deserializePreferenceValue(bytes)
      throw AssertionError("Application-defined preference class was accepted")
    } catch (_: java.io.InvalidClassException) {
      // Expected: custom classes are not part of the RemotePreferences value contract.
    }
  }

  @Test
  fun rejectsNonStringSets() {
    assertFalse(isSupportedPreferenceValue(setOf(1, 2, 3)))
  }
}
