package korlibs.io.file.std

import korlibs.io.async.suspendTest
import korlibs.io.file.*
import korlibs.io.lang.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class JvmLocalFileTest {
	@Test
	fun test() = suspendTest {
		val tmpVfs = File(Environment.tempPath).toVfs().jail()
		tmpVfs["korio-test"].mkdir()
		tmpVfs["korio-test"]["demo.txt"].writeString("HELLO")
		assertEquals(listOf("demo.txt"), tmpVfs["korio-test"].listRecursive().map { it.baseName }.toList())
	}
}
