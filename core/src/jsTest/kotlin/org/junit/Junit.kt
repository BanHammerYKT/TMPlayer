// The few names of JUnit 4 the JVM tests copied into jsTest use (core/js-test-sources.txt), over
// kotlin.test, so the same files run on Kotlin/JS unchanged. JUnit's overloads are kept where they
// change what is compared: a Long against a literal, and doubles within a delta.
package org.junit

typealias Test = kotlin.test.Test
typealias Before = kotlin.test.BeforeTest
typealias After = kotlin.test.AfterTest
typealias Ignore = kotlin.test.Ignore

object Assert {
    fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
    fun assertEquals(message: String?, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)
    fun assertEquals(expected: Long, actual: Long) = kotlin.test.assertEquals(expected, actual)
    fun assertEquals(message: String?, expected: Long, actual: Long) = kotlin.test.assertEquals(expected, actual, message)
    fun assertEquals(expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta)
    fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta, message)
    fun assertEquals(expected: Float, actual: Float, delta: Float) = kotlin.test.assertEquals(expected, actual, delta)
    fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) = kotlin.test.assertEquals(expected, actual, delta, message)
    fun assertNotEquals(unexpected: Any?, actual: Any?) = kotlin.test.assertNotEquals(unexpected, actual)
    fun assertNotEquals(message: String?, unexpected: Any?, actual: Any?) = kotlin.test.assertNotEquals(unexpected, actual, message)
    fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)
    fun assertTrue(message: String?, condition: Boolean) = kotlin.test.assertTrue(condition, message)
    fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)
    fun assertFalse(message: String?, condition: Boolean) = kotlin.test.assertFalse(condition, message)
    fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)
    fun assertNull(message: String?, actual: Any?) = kotlin.test.assertNull(actual, message)
    fun assertNotNull(actual: Any?) { kotlin.test.assertNotNull(actual) }
    fun assertNotNull(message: String?, actual: Any?) { kotlin.test.assertNotNull(actual, message) }
    fun assertSame(expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual)
    fun assertArrayEquals(expected: ByteArray?, actual: ByteArray?) = kotlin.test.assertContentEquals(expected, actual)
    fun fail(message: String?): Nothing = kotlin.test.fail(message)
}
