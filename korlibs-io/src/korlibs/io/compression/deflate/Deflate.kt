package korlibs.io.compression.deflate

import korlibs.compression.deflate.*
import korlibs.memory.FixedSizeByteArrayBuilder
import korlibs.math.unsigned
import korlibs.io.compression.CompressionContext
import korlibs.io.compression.CompressionMethod
import korlibs.io.compression.util.BitReader
import korlibs.io.compression.util.HuffmanTree
import korlibs.io.compression.util.SlidingWindow
import korlibs.io.experimental.KorioExperimentalApi
import korlibs.io.stream.AsyncOutputStream
import korlibs.io.stream.getAvailable
import korlibs.io.stream.hasAvailable
import korlibs.io.stream.readBytesExact
import korlibs.io.stream.write16LE
import korlibs.io.stream.write8
import korlibs.io.stream.writeBytes
import kotlin.math.min

expect fun Deflate(windowBits: Int): CompressionMethod

val Deflate: CompressionMethod by lazy { Deflate(15) }

@OptIn(KorioExperimentalApi::class, ExperimentalStdlibApi::class)
open class DeflatePortable(val windowBits: Int) : CompressionMethod {
	val deflater = DeflaterPortable(windowBits)
    override val name: String get() = "DEFLATE"

    override suspend fun compress(
		i: BitReader,
		o: AsyncOutputStream,
		context: CompressionContext
	) {
		deflater.compress(i.toDeflater(), o.toDeflater(), context.level.toFloat() / 10f)
	}

	override suspend fun uncompress(reader: BitReader, out: AsyncOutputStream) {
		deflater.uncompress(reader.toDeflater(), out.toDeflater())
	}

	fun BitReader.toDeflater(): DeflaterPortable.DeflateBitReader = object : DeflaterPortable.DeflateBitReader {
		override val bigChunkSize: Int get() = this@toDeflater.bigChunkSize
		override val readWithSize: Int get() = this@toDeflater.readWithSize
		override val bitsavailable: Int get() = this@toDeflater.bitsavailable
		override fun ensureBits(bits: Int) = this@toDeflater.ensureBits(bits)
		override suspend fun hasAvailable(): Boolean = this@toDeflater.hasAvailable()
		override suspend fun getAvailable(): Long  = this@toDeflater.getAvailable()
		override suspend fun abytes(count: Int): ByteArray = this@toDeflater.abytes(count)
		override fun su16LE(): Int = this@toDeflater.su16LE()
		override fun sreadBit(): Boolean = this@toDeflater.sreadBit()
		override fun skipBits(bits: Int)  = this@toDeflater.skipBits(bits)
		override fun peekBits(count: Int): Int  = this@toDeflater.peekBits(count)
		override fun readBits(count: Int): Int  = this@toDeflater.readBits(count)
		override suspend fun readBytesExact(count: Int): ByteArray = this@toDeflater.readBytesExact(count)
		override suspend fun prepareBigChunkIfRequired() = this@toDeflater.prepareBigChunkIfRequired()
	}

	fun AsyncOutputStream.toDeflater(): DeflaterPortable.DeflateAsyncOutputStream = object : DeflaterPortable.DeflateAsyncOutputStream {
		override suspend fun write(bytes: ByteArray, offset: Int, size: Int) = this@toDeflater.write(bytes, offset, size)
		override suspend fun write8(value: Int) = this@toDeflater.write8(value)
		override suspend fun write16LE(value: Int) = this@toDeflater.write16LE(value)
		override suspend fun writeBytes(bytes: ByteArray) = this@toDeflater.writeBytes(bytes)
	}

	companion object : DeflatePortable(15)
}
