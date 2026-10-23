// kotlinx.coroutines has no runBlocking on Kotlin/JS: the copied JVM tests that use one run it as
// runTest, whose promise the test runner waits for.
package kotlinx.coroutines

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

fun runBlocking(block: suspend CoroutineScope.() -> Unit): TestResult = runTest { block() }
