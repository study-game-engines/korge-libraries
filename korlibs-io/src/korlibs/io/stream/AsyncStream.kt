@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package korlibs.io.stream

import korlibs.datastructure.*
import korlibs.datastructure.iterators.*
import korlibs.io.async.*
import korlibs.io.file.*
import korlibs.io.file.std.*
import korlibs.io.internal.*
import korlibs.io.lang.*
import korlibs.io.util.*
import korlibs.math.*
import korlibs.memory.*
import korlibs.platform.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.native.concurrent.*

//interface SmallTemp {
//	val smallTemp: ByteArray
//}

//interface AsyncBaseStream : AsyncCloseable, SmallTemp {

fun List<AsyncInputStreamWithLength>.combine(): AsyncInputStreamWithLength {
	val list = this
	return object : AsyncInputStreamWithLength {
		override suspend fun getPosition(): Long = list.map { it.getPosition() }.sum()
		override suspend fun getLength(): Long = list.map { it.getLength() }.sum()

		override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
			list.fastForEach { i ->
				val read = i.read(buffer, offset, len)
				if (read > 0) return read
			}
			return -1
		}

		override suspend fun close() {
			list.fastForEach { i ->
				i.close()
			}
		}
	}
}

operator fun AsyncInputStreamWithLength.plus(other: AsyncInputStreamWithLength): AsyncInputStreamWithLength = listOf(this, other).combine()

suspend fun AsyncInputStreamWithLength.getAvailable(): Long = this.getLength() - this.getPosition()
suspend fun AsyncInputStreamWithLength.hasAvailable(): Boolean = getAvailable() > 0
suspend fun AsyncInputStreamWithLength.supportsAvailable() = kotlin.runCatching { hasAvailable() }.isSuccess

fun AsyncBaseStream.toAsyncStream(): AsyncStream {
	val input = this as? AsyncInputStream
	val output = this as? AsyncOutputStream
    val rlenSet = this as? AsyncLengthStream
	val rlenGet = this as? AsyncGetLengthStream
	val closeable = this

	return object : AsyncStreamBase() {
		var expectedPosition: Long = 0L
		//val events = arrayListOf<String>()

		override suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
			if (input == null) throw UnsupportedOperationException()
			//events += "before_read:actualPosition=$position,position=$expectedPosition"
			checkPosition(position)
			val read = input.read(buffer, offset, len)
			//events += "read:$read"
			if (read > 0) expectedPosition += read
			return read
		}

		override suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) {
			if (output == null) throw UnsupportedOperationException()
			checkPosition(position)
			output.write(buffer, offset, len)
			expectedPosition += len
		}

		private fun checkPosition(position: Long) {
			if (position != expectedPosition) {
				throw SeekNotSupportedException()
			}
		}

		override suspend fun setLength(value: Long) = rlenSet?.setLength(value) ?: throw UnsupportedOperationException()
		override suspend fun getLength(): Long = rlenGet?.getLength() ?: throw UnsupportedOperationException()
		override suspend fun close() = closeable.close()
	}.toAsyncStream()
}

open class SeekNotSupportedException(message: String = "Seeking not supported!") : UnsupportedOperationException(message)

suspend fun AsyncStreamBase.readBytes(position: Long, count: Int): ByteArray {
	val out = ByteArray(count)
	val readLen = read(position, out, 0, out.size)
	return out.copyOf(readLen)
}

inline fun <T> AsyncStream.keepPosition(callback: () -> T): T {
	val old = this.position
	try {
		return callback()
	} finally {
		this.position = old
	}
}

suspend fun AsyncPositionLengthStream.getAvailable(): Long = getLength() - getPosition()
suspend fun AsyncPositionLengthStream.eof(): Boolean = this.getAvailable() <= 0L

class SliceAsyncStreamBase(
	internal val base: AsyncStreamBase,
	internal val baseStart: Long,
	internal val baseEnd: Long,
	internal val closeParent: Boolean
) : AsyncStreamBase() {
    init {
        //check(baseStart < 0) { "baseStart negative: $baseStart" }
        //check(baseEnd < baseStart) { "Invalid baseEnd=$baseEnd" }
    }
	//init {
	//	base.refCount++
	//}

	internal val baseLength = baseEnd - baseStart

	private fun clampPosition(position: Long) = position.clamp(baseStart, baseEnd)

	private fun clampPositionLen(position: Long, len: Int): Pair<Long, Int> {
		if (position < 0L) throw IllegalArgumentException("Invalid position")
		val targetStartPosition = clampPosition(this.baseStart + position)
		val targetEndPosition = clampPosition(targetStartPosition + len)
		val targetLen = (targetEndPosition - targetStartPosition).toInt()
		return Pair(targetStartPosition, targetLen)
	}

	override suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
		val (targetStartPosition, targetLen) = clampPositionLen(position, len)
		return base.read(targetStartPosition, buffer, offset, targetLen)
	}

	override suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) {
		val (targetStartPosition, targetLen) = clampPositionLen(position, len)
		return base.write(targetStartPosition, buffer, offset, targetLen)
	}

	override suspend fun getLength(): Long = baseLength

	override suspend fun close() {
		if (closeParent) {
			base.close()
		}
	}

	override fun toString(): String = "SliceAsyncStreamBase($base, $baseStart, $baseEnd)"
}

fun AsyncStream.buffered(blockSize: Int = 2048, blocksToRead: Int = 0x10) = BufferedStreamBase(this.base, blockSize, blocksToRead).toAsyncStream(this.position)

class BufferedStreamBase(val base: AsyncStreamBase, val blockSize: Int = 2048, val blocksToRead: Int = 0x10) : AsyncStreamBase() {
	private val bsize = blockSize * blocksToRead

	override suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = _read(position, buffer, offset, len)

	var cachedData = byteArrayOf()
	var cachedSector = -1L

	override fun toString(): String = "Buffered[$blocksToRead*$blockSize]:$base"

	suspend fun _read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
		if (base.hasLength() && position >= base.getLength()) return -1
		val sector = position / bsize
		if (cachedSector != sector) {
			val pos = sector * bsize
			val len = if (base.hasLength()) minOf(bsize.toLong(), base.getLength() - pos).toInt() else bsize
			//println("$base, position=$position, offset=$offset, len=$len, base.getLength()=${base.getLength()}\n")
			cachedData = base.readBytes(pos, len)
			cachedSector = sector
		}
		val soffset = (position % bsize).toInt()
		val available = cachedData.size - soffset
		val toRead = min(available, len)
		arraycopy(cachedData, soffset, buffer, offset, toRead)
		return toRead
	}

	override suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) {
		base.write(position, buffer, offset, len)
	}

	override suspend fun setLength(value: Long) = base.setLength(value)
	override suspend fun getLength(): Long = base.getLength()
	override suspend fun close() = base.close()
}

suspend fun AsyncBufferedInputStream.readBufferedLine(limit: Int = 0x1000, charset: Charset = UTF8) =
	readUntil('\n'.toByte(), including = false, limit = limit).toString(charset)

fun AsyncInputStream.bufferedInput(bufferSize: Int = 0x2000): AsyncBufferedInputStream =
	AsyncBufferedInputStream(this, bufferSize)

class AsyncBufferedInputStream(val base: AsyncInputStream, val bufferSize: Int = 0x2000) : AsyncInputStream {
	private val buf = ByteArrayDeque(bufferSize)

	private val queue = AsyncThread()
	private val temp = ByteArray(bufferSize)

	suspend fun require(len: Int = 1) = queue {
		while (buf.availableRead < len) {
			val read = base.read(temp, 0, temp.size)
			if (read <= 0) break
			buf.write(temp, 0, read)
		}
	}

	override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
        if (buf.availableRead < len) require()
		return buf.read(buffer, offset, len)
	}

	override suspend fun read(): Int {
		if (buf.availableRead < 1) require()
		return buf.readByte()
	}

	suspend fun readUntil(end: Byte, including: Boolean = true, limit: Int = 0x1000): ByteArray {
		val out = ByteArrayBuilder()
		loop@while (true) {
			require()
            if (buf.availableRead == 0) break@loop
            while (buf.availableRead > 0) {
                val byteInt = buf.readByte()
                if (byteInt < 0) break@loop
                val byte = byteInt.toByte()
                //println("chunk: $chunk, ${chunk.size}")
                if (including || byte != end) {
                    out.append(byte)
                }
                if (byte == end || out.size >= limit) break@loop
            }
		}
		return out.toByteArray()
	}

	override suspend fun close() {
		base.close()
	}
}

suspend fun AsyncStream.sliceWithSize(start: Long, length: Long, closeParent: Boolean = false): AsyncStream = sliceWithBounds(start, start + length, closeParent)
suspend fun AsyncStream.sliceWithSize(start: Int, length: Int, closeParent: Boolean = false): AsyncStream =
	sliceWithBounds(start.toLong(), (start + length).toLong(), closeParent)

suspend fun AsyncStream.slice(range: IntRange, closeParent: Boolean = false): AsyncStream =
	sliceWithBounds(range.start.toLong(), (range.endInclusive.toLong() + 1), closeParent)

suspend fun AsyncStream.slice(range: LongRange, closeParent: Boolean = false): AsyncStream = sliceWithBounds(range.start, (range.endInclusive + 1), closeParent)

suspend fun AsyncStream.sliceWithBounds(start: Long, end: Long, closeParent: Boolean = false): AsyncStream {
	val rlen = if (this.hasLength()) this.getLength() else end
    val len = if (rlen >= 0L) rlen else end
	val clampedStart = start.clamp(0, len)
	val clampedEnd = end.clamp(0, len)

	val base = this.base
	return when (base) {
        is SliceAsyncStreamBase -> {
            SliceAsyncStreamBase(
                base.base,
                base.baseStart + clampedStart,
                base.baseStart + clampedEnd,
                closeParent
            )
        }
        else -> {
            SliceAsyncStreamBase(this.base, clampedStart, clampedEnd, closeParent)
        }
    }.toAsyncStream()
}

suspend fun AsyncStream.sliceStart(start: Long = 0L, closeParent: Boolean = false): AsyncStream = sliceWithBounds(start, this.getLength(), closeParent)
suspend fun AsyncStream.sliceHere(closeParent: Boolean = false): AsyncStream = this.sliceWithSize(position, this.getLength(), closeParent)

suspend fun AsyncStream.readSlice(length: Long): AsyncStream {
	val start = getPosition()
	val out = this.sliceWithSize(start, length)
	setPosition(start + length)
	return out
}

suspend fun AsyncStream.readStream(length: Int): AsyncStream = readSlice(length.toLong())
suspend fun AsyncStream.readStream(length: Long): AsyncStream = readSlice(length)

suspend fun AsyncInputStream.readStringz(charset: Charset = UTF8): String {
	val buf = ByteArrayBuilder()
	val temp = ByteArray(1)
	while (true) {
		val read = read(temp, 0, 1)
		if (read <= 0) break
		if (temp[0] == 0.toByte()) break
		buf.append(temp[0])
	}
	return buf.toByteArray().toString(charset)
}

suspend fun AsyncInputStream.readStringz(len: Int, charset: Charset = UTF8): String {
	val res = readBytesExact(len)
	val index = res.indexOf(0.toByte())
	return res.copyOf(if (index < 0) len else index).toString(charset)
}

suspend fun AsyncInputStream.readString(len: Int, charset: Charset = UTF8): String =
	readBytesExact(len).toString(charset)

suspend fun AsyncOutputStream.writeStringz(str: String, charset: Charset = UTF8) =
	this.writeBytes(str.toBytez(charset))

suspend fun AsyncOutputStream.writeStringz(str: String, len: Int, charset: Charset = UTF8) =
	this.writeBytes(str.toBytez(len, charset))

suspend fun AsyncOutputStream.writeString(string: String, charset: Charset = UTF8): Unit =
	writeBytes(string.toByteArray(charset))

suspend fun AsyncInputStream.readExact(buffer: ByteArray, offset: Int, len: Int) {
	var remaining = len
	var coffset = offset
	val reader = this
	while (remaining > 0) {
		val read = reader.read(buffer, coffset, remaining)
		if (read < 0) break
		if (read == 0) throw EOFException("Not enough data. Expected=$len, Read=${len - remaining}, Remaining=$remaining")
		coffset += read
		remaining -= read
	}
}

//val READ_SMALL_TEMP by threadLocal { ByteArray(8) }
//suspend private fun AsyncInputStream.readSmallTempExact(len: Int, temp: ByteArray): ByteArray = temp.apply { readExact(temp, 0, len) }
//suspend private fun AsyncInputStream.readSmallTempExact(len: Int): ByteArray = readSmallTempExact(len, READ_SMALL_TEMP)

@PublishedApi
internal suspend inline fun <R> AsyncInputStream.readSmallTempExact(size: Int, callback: ByteArray.() -> R): R = smallBytesPool.allocThis {
	val read = read(this, 0, size)
	if (read != size) error("Couldn't read exact size=$size but read=$read")
	callback()
}


private suspend fun AsyncInputStream.readTempExact(len: Int, temp: ByteArray): ByteArray =
	temp.apply { readExact(temp, 0, len) }
//suspend private fun AsyncInputStream.readTempExact(len: Int): ByteArray = readTempExact(len, BYTES_TEMP)

suspend fun AsyncInputStream.read(data: ByteArray): Int = read(data, 0, data.size)
suspend fun AsyncInputStream.read(data: UByteArray): Int = read(data.asByteArray(), 0, data.size)

val EMPTY_BYTE_ARRAY = ByteArray(0)

suspend fun AsyncInputStream.readBytesUpToFirst(len: Int): ByteArray {
	val out = ByteArray(len)
	val read = read(out, 0, len)
	if (read <= 0) return EMPTY_BYTE_ARRAY
	return out.copyOf(read)
}

suspend fun AsyncInputStream.readBytesUpTo(out: ByteArray, offset: Int, len: Int): Int {
    var total = 0
    var pending = len
    var offset = offset
    while (true) {
        val result = read(out, offset, pending)
        if (result <= 0) break
        offset += result
        pending -= result
        total += result
    }
    return total
}

suspend fun AsyncInputStream.readBytesUpToCopy(out: ByteArray): ByteArray {
    val pos = readBytesUpTo(out, 0, out.size)
    return if (out.size == pos) out else out.copyOf(pos)
}

suspend fun AsyncInputStream.readBytesUpTo(len: Int): ByteArray {
	val BYTES_TEMP_SIZE = 0x1000

    if (len <= BYTES_TEMP_SIZE) return readBytesUpToCopy(ByteArray(len))
    if (this is AsyncPositionLengthStream) return readBytesUpToCopy(ByteArray(min(len, this.getAvailable().toIntClamp())))

    var pending = len
    val temp = ByteArray(BYTES_TEMP_SIZE)
    val bout = ByteArrayBuilder()
    while (pending > 0) {
        val read = this.read(temp, 0, min(temp.size, pending))
        if (read <= 0) break
        bout.append(temp, 0, read)
        pending -= read
    }
    return bout.toByteArray()
}

suspend fun AsyncInputStream.readBytesExact(len: Int): ByteArray = ByteArray(len).apply { readExact(this, 0, len) }

//suspend fun AsyncInputStream.readU8(): Int = readBytesExact(1).readU8(0)
suspend fun AsyncInputStream.readU8(): Int = read()

suspend fun AsyncInputStream.readS8(): Int = read().toByte().toInt()
suspend fun AsyncInputStream.readU16LE(): Int = readSmallTempExact(2) { getU16LE(0) }
suspend fun AsyncInputStream.readU24LE(): Int = readSmallTempExact(3) { getU24LE(0) }
suspend fun AsyncInputStream.readU32LE(): Long = readSmallTempExact(4) { getU32LE(0) }
suspend fun AsyncInputStream.readS16LE(): Int = readSmallTempExact(2) { getS16LE(0) }
suspend fun AsyncInputStream.readS24LE(): Int = readSmallTempExact(3) { getS24LE(0) }
suspend fun AsyncInputStream.readS32LE(): Int = readSmallTempExact(4) { getS32LE(0) }
suspend fun AsyncInputStream.readS64LE(): Long = readSmallTempExact(8) { getS64LE(0) }
suspend fun AsyncInputStream.readF32LE(): Float = readSmallTempExact(4) { getF32LE(0) }
suspend fun AsyncInputStream.readF64LE(): Double = readSmallTempExact(8) { getF64LE(0) }
suspend fun AsyncInputStream.readU16BE(): Int = readSmallTempExact(2) { getU16BE(0) }
suspend fun AsyncInputStream.readU24BE(): Int = readSmallTempExact(3) { getU24BE(0) }
suspend fun AsyncInputStream.readU32BE(): Long = readSmallTempExact(4) { getU32BE(0) }
suspend fun AsyncInputStream.readS16BE(): Int = readSmallTempExact(2) { getS16BE(0) }
suspend fun AsyncInputStream.readS24BE(): Int = readSmallTempExact(3) { getS24BE(0) }
suspend fun AsyncInputStream.readS32BE(): Int = readSmallTempExact(4) { getS32BE(0) }
suspend fun AsyncInputStream.readS64BE(): Long = readSmallTempExact(8) { getS64BE(0) }
suspend fun AsyncInputStream.readF32BE(): Float = readSmallTempExact(4) { getF32BE(0) }
suspend fun AsyncInputStream.readF64BE(): Double = readSmallTempExact(8) { getF64BE(0) }

suspend fun AsyncInputStream.readU16(endian: Endian): Int = if (endian == Endian.LITTLE_ENDIAN) readU16LE() else readU16BE()
suspend fun AsyncInputStream.readU24(endian: Endian): Int = if (endian == Endian.LITTLE_ENDIAN) readU24LE() else readU24BE()
suspend fun AsyncInputStream.readU32(endian: Endian): Long = if (endian == Endian.LITTLE_ENDIAN) readU32LE() else readU32BE()
suspend fun AsyncInputStream.readS16(endian: Endian): Int = if (endian == Endian.LITTLE_ENDIAN) readS16LE() else readS16BE()
suspend fun AsyncInputStream.readS24(endian: Endian): Int = if (endian == Endian.LITTLE_ENDIAN) readS24LE() else readS24BE()
suspend fun AsyncInputStream.readS32(endian: Endian): Int = if (endian == Endian.LITTLE_ENDIAN) readS32LE() else readS32BE()
suspend fun AsyncInputStream.readS64(endian: Endian): Long = if (endian == Endian.LITTLE_ENDIAN) readS64LE() else readS64BE()
suspend fun AsyncInputStream.readF32(endian: Endian): Float = if (endian == Endian.LITTLE_ENDIAN) readF32LE() else readF32BE()
suspend fun AsyncInputStream.readF64(endian: Endian): Double = if (endian == Endian.LITTLE_ENDIAN) readF64LE() else readF64BE()

suspend fun AsyncInputStream.readAll(): ByteArray {
	return try {
        when {
            this is AsyncGetPositionStream && this is AsyncGetLengthStream -> {
                val available = this.getLength() - this.getPosition()
                this.readBytesExact(available.toInt())
            }
            this is AsyncStream && this.hasAvailable() -> {
                val available = this.getAvailable().toInt()
                this.readBytesExact(available)
            }
            else -> {
                val out = ByteArrayBuilder()
                val temp = ByteArray(0x1000)
                while (true) {
                    val r = this.read(temp, 0, temp.size)
                    if (r <= 0) break
                    out.append(temp, 0, r)
                }
                out.toByteArray()
            }
        }
	} finally {
		this.close()
	}
}

// readAll alias
suspend fun AsyncInputStream.readAvailable(): ByteArray = readAll()

suspend fun AsyncInputStream.skip(count: Int) {
	if (this is AsyncPositionLengthStream) {
		this.setPosition(this.getPosition() + count)
	} else {
		val temp = ByteArray(min(0x1000, count))
		var remaining = count
		while (remaining > 0) {
			val toRead = min(remaining, count)
			readTempExact(toRead, temp)
			remaining -= toRead
		}
	}
}

suspend fun AsyncInputStream.readUByteArray(count: Int): UByteArray = readBytesExact(count).asUByteArray()
suspend fun AsyncInputStream.readShortArrayLE(count: Int): ShortArray = readBytesExact(count * 2).getS16ArrayLE(
    0,
    count
)
suspend fun AsyncInputStream.readShortArrayBE(count: Int): ShortArray = readBytesExact(count * 2).getS16ArrayBE(
    0,
    count
)
suspend fun AsyncInputStream.readCharArrayLE(count: Int): CharArray = readBytesExact(count * 2).getU16ArrayLE(0, count)
suspend fun AsyncInputStream.readCharArrayBE(count: Int): CharArray = readBytesExact(count * 2).getU16ArrayBE(0, count)
suspend fun AsyncInputStream.readIntArrayLE(count: Int): IntArray = readBytesExact(count * 4).getS32ArrayLE(0, count)
suspend fun AsyncInputStream.readIntArrayBE(count: Int): IntArray = readBytesExact(count * 4).getS32ArrayBE(0, count)
suspend fun AsyncInputStream.readLongArrayLE(count: Int): LongArray = readBytesExact(count * 8).getS64ArrayLE(0, count)
suspend fun AsyncInputStream.readLongArrayBE(count: Int): LongArray = readBytesExact(count * 8).getS64ArrayLE(0, count)
suspend fun AsyncInputStream.readFloatArrayLE(count: Int): FloatArray = readBytesExact(count * 4).getF32ArrayLE(
    0,
    count
)
suspend fun AsyncInputStream.readFloatArrayBE(count: Int): FloatArray = readBytesExact(count * 4).getF32ArrayBE(
    0,
    count
)
suspend fun AsyncInputStream.readDoubleArrayLE(count: Int): DoubleArray =
    readBytesExact(count * 8).getF64ArrayLE(0, count)
suspend fun AsyncInputStream.readDoubleArrayBE(count: Int): DoubleArray =
    readBytesExact(count * 8).getF64ArrayBE(0, count)

suspend fun AsyncInputStream.readShortArray(count: Int, endian: Endian): ShortArray = if (endian.isLittle) readShortArrayLE(count) else readShortArrayBE(count)
suspend fun AsyncInputStream.readCharArray(count: Int, endian: Endian): CharArray = if (endian.isLittle) readCharArrayLE(count) else readCharArrayBE(count)
suspend fun AsyncInputStream.readIntArray(count: Int, endian: Endian): IntArray = if (endian.isLittle) readIntArrayLE(count) else readIntArrayBE(count)
suspend fun AsyncInputStream.readLongArray(count: Int, endian: Endian): LongArray = if (endian.isLittle) readLongArrayLE(count) else readLongArrayBE(count)
suspend fun AsyncInputStream.readFloatArray(count: Int, endian: Endian): FloatArray = if (endian.isLittle) readFloatArrayLE(count) else readFloatArrayBE(count)
suspend fun AsyncInputStream.readDoubleArray(count: Int, endian: Endian): DoubleArray = if (endian.isLittle) readDoubleArrayLE(count) else readDoubleArrayBE(count)

suspend fun AsyncOutputStream.writeTempBytes(size: Int, block: ByteArray.() -> Unit) {
    if (size <= BYTES_TEMP_SIZE) {
        bytesTempPool.allocThis {
            this@writeTempBytes.write(this@allocThis.apply(block), 0, size)
        }
    } else {
        write(ByteArray(size).apply(block))
    }
}

suspend fun AsyncOutputStream.writeBytes(data: ByteArray): Unit = write(data, 0, data.size)
suspend fun AsyncOutputStream.writeBytes(data: ByteArray, position: Int, length: Int): Unit = write(data, position, length)
suspend fun AsyncOutputStream.write8(v: Int): Unit = write(v)
suspend fun AsyncOutputStream.write16LE(v: Int): Unit = smallBytesPool.alloc { it.set16LE(0, v); write(it, 0, 2) }
suspend fun AsyncOutputStream.write24LE(v: Int): Unit = smallBytesPool.alloc { it.set24LE(0, v); write(it, 0, 3) }
suspend fun AsyncOutputStream.write32LE(v: Int): Unit = smallBytesPool.alloc { it.set32LE(0, v); write(it, 0, 4) }
suspend fun AsyncOutputStream.write32LE(v: Long): Unit = smallBytesPool.alloc { it.set32LE(0, v); write(it, 0, 4) }
suspend fun AsyncOutputStream.write64LE(v: Long): Unit = smallBytesPool.alloc { it.set64LE(0, v); write(it, 0, 8) }
suspend fun AsyncOutputStream.writeF32LE(v: Float): Unit = smallBytesPool.alloc { it.setF32LE(0, v); write(it, 0, 4) }
suspend fun AsyncOutputStream.writeF64LE(v: Double): Unit = smallBytesPool.alloc { it.setF64LE(0, v); write(it, 0, 8) }

suspend fun AsyncOutputStream.write16BE(v: Int): Unit = smallBytesPool.alloc { it.set16BE(0, v); write(it, 0, 2) }
suspend fun AsyncOutputStream.write24BE(v: Int): Unit = smallBytesPool.alloc { it.set24BE(0, v); write(it, 0, 3) }
suspend fun AsyncOutputStream.write32BE(v: Int): Unit = smallBytesPool.alloc { it.set32BE(0, v); write(it, 0, 4) }
suspend fun AsyncOutputStream.write32BE(v: Long): Unit = smallBytesPool.alloc { it.set32BE(0, v); write(it, 0, 4) }
suspend fun AsyncOutputStream.write64BE(v: Long): Unit = smallBytesPool.alloc { it.set64BE(0, v); write(it, 0, 8) }
suspend fun AsyncOutputStream.writeF32BE(v: Float): Unit = smallBytesPool.alloc { it.setF32BE(0, v); write(it, 0, 4) }
suspend fun AsyncOutputStream.writeF64BE(v: Double): Unit = smallBytesPool.alloc { it.setF64BE(0, v); write(it, 0, 8) }

suspend fun AsyncOutputStream.writeStream(source: AsyncInputStream): Long = source.copyTo(this)
suspend fun AsyncOutputStream.writeFile(source: VfsFile): Long =
	source.openUse(VfsOpenMode.READ) { this@writeFile.writeStream(this) }

suspend inline fun AsyncInputStream.consume(autoclose: Boolean = true, temp: ByteArray = ByteArray(0x10000), block: (data: ByteArray, offset: Int, size: Int) -> Unit) {
    try {
        while (true) {
            val read = read(temp, 0, temp.size)
            if (read <= 0) break
            block(temp, 0, read)
        }
    } finally {
        if (autoclose) close()
    }
}

//suspend fun AsyncInputStream.copyTo(target: AsyncOutputStream, chunkSize: Int = 256 * 1024): Long {
suspend fun AsyncInputStream.copyTo(target: AsyncOutputStream, chunkSize: Int = 8 * 1024 * 1024): Long {
	// Optimization to reduce suspensions
	if (this is AsyncStream && base is MemoryAsyncStreamBase) {
		val base = base as MemoryAsyncStreamBase
		target.write(base.data.data, position.toInt(), base.ilength - position.toInt())
		return base.ilength.toLong()
	}

    val rchunkSize = if (this is AsyncGetLengthStream) min(this.getLength(), chunkSize.toLong()).toInt() else chunkSize

    var totalCount = 0L
    this.consume(autoclose = false, temp = ByteArray(rchunkSize)) { data, offset, size ->
        //println("write. offset=$offset, size=$size")
        target.write(data, offset, size)
        totalCount += size
    }
	return totalCount
}

suspend fun AsyncStream.writeToAlign(alignment: Int, value: Int = 0) {
	val nextPosition = getPosition().nextAlignedTo(alignment.toLong())
	val data = ByteArray((nextPosition - getPosition()).toInt())
	data.fill(value.toByte())
	writeBytes(data)
}

fun AsyncStream.skip(count: Int): AsyncStream {
    position += count
    return this
}
fun AsyncStream.skipToAlign(alignment: Int) { position = position.nextAlignedTo(alignment.toLong()) }
fun AsyncStream.skipToAlign(alignment: Int, offset: Int) { position = (position + offset).nextAlignedTo(alignment.toLong()) - offset }
suspend fun AsyncStream.truncate() = setLength(position)

suspend fun AsyncOutputStream.writeCharArrayLE(array: CharArray) = writeTempBytes(array.size * 2) { setArrayLE(0, array) }
suspend fun AsyncOutputStream.writeShortArrayLE(array: ShortArray) = writeTempBytes(array.size * 2) { setArrayLE(0, array) }
suspend fun AsyncOutputStream.writeIntArrayLE(array: IntArray) = writeTempBytes(array.size * 4) { setArrayLE(0, array) }
suspend fun AsyncOutputStream.writeLongArrayLE(array: LongArray) = writeTempBytes(array.size * 8) { setArrayLE(0, array) }
suspend fun AsyncOutputStream.writeFloatArrayLE(array: FloatArray) = writeTempBytes(array.size * 4) { setArrayLE(0, array) }
suspend fun AsyncOutputStream.writeDoubleArrayLE(array: DoubleArray) = writeTempBytes(array.size * 8) { setArrayLE(0, array) }
suspend fun AsyncOutputStream.writeCharArrayBE(array: CharArray) = writeTempBytes(array.size * 2) { setArrayBE(0, array) }
suspend fun AsyncOutputStream.writeShortArrayBE(array: ShortArray) = writeTempBytes(array.size * 2) { setArrayBE(0, array) }
suspend fun AsyncOutputStream.writeIntArrayBE(array: IntArray) = writeTempBytes(array.size * 4) { setArrayBE(0, array) }
suspend fun AsyncOutputStream.writeLongArrayBE(array: LongArray) = writeTempBytes(array.size * 8) { setArrayBE(0, array) }
suspend fun AsyncOutputStream.writeFloatArrayBE(array: FloatArray) = writeTempBytes(array.size * 4) { setArrayBE(0, array) }
suspend fun AsyncOutputStream.writeDoubleArrayBE(array: DoubleArray) = writeTempBytes(array.size * 8) { setArrayBE(0, array) }

suspend fun AsyncInputStream.readUntil(endByte: Byte, limit: Int = 0x1000, temp: ByteArray = ByteArray(1)): ByteArray {
	val out = ByteArrayBuilder()
	try {
		while (true) {
            readExact(temp, 0, 1)
			val c = temp[0]
			//val c = readS8().toByte()
			if (c == endByte) break
			out.append(c)
			if (out.size >= limit) break
		}
	} catch (e: EOFException) {
	}
	//println("AsyncInputStream.readUntil: '${out.toString(UTF8).replace('\r', ';').replace('\n', '.')}'")
	return out.toByteArray()
}

suspend fun AsyncInputStream.readLine(eol: Char = '\n', charset: Charset = UTF8, initialCapacity: Int = 4096): String {
	val temp = ByteArray(1)
	val out = ByteArrayBuilder(initialCapacity)
	try {
		while (true) {
            //println("RCHAR")
            readExact(temp, 0, 1)
			val c = temp[0]
            //println("CHAR: c='${c.toChar()}' (${c.toInt()})")
			//val c = readS8().toByte()
			if (c == eol.toInt().toByte()) break
			out.append(c)
		}
	} catch (e: EOFException) {
        //e.printStackTrace()
	}
	return out.toByteArray().toString(charset)
}



private suspend inline fun <T> doIo(dispatcher: CoroutineDispatcher? = null, crossinline block: () -> T): T = when {
	dispatcher != null -> withContext(dispatcher) { block() }
	else -> block()
}

fun SyncInputStream.toAsync(dispatcher: CoroutineDispatcher? = null): AsyncInputStream = object : AsyncInputStreamWithLength {
	val sync = this@toAsync
	private suspend inline fun <T> doIo(crossinline block: () -> T): T = doIo(dispatcher, block)
	override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int = doIo { sync.read(buffer, offset, len) }
	override suspend fun close(): Unit = doIo { (sync as? korlibs.datastructure.closeable.Closeable)?.close() }
	override suspend fun getPosition(): Long = doIo { (sync as? SyncPositionStream)?.position } ?: super.getPosition()
	override suspend fun getLength(): Long = doIo { (sync as? SyncLengthStream)?.length } ?: super.getLength()
}

fun SyncOutputStream.toAsync(dispatcher: CoroutineDispatcher? = null): AsyncOutputStream = object : AsyncOutputStream {
	val sync = this@toAsync
	private suspend inline fun <T> doIo(crossinline block: () -> T): T = doIo(dispatcher, block)
	override suspend fun write(buffer: ByteArray, offset: Int, len: Int) = doIo { sync.write(buffer, offset, len) }
	override suspend fun close(): Unit = doIo { (sync as? korlibs.datastructure.closeable.Closeable)?.close() }
}

@Deprecated("", ReplaceWith("toAsync(dispatcher) as AsyncInputStreamWithLength"))
fun SyncInputStream.toAsyncInputStream(dispatcher: CoroutineDispatcher? = null): AsyncInputStreamWithLength = toAsync(dispatcher) as AsyncInputStreamWithLength

@Deprecated("", ReplaceWith("toAsync(dispatcher)"))
fun SyncOutputStream.toAsyncOutputStream(dispatcher: CoroutineDispatcher? = null): AsyncOutputStream = toAsync(dispatcher)

fun AsyncStream.asVfsFile(name: String = "unknown.bin"): VfsFile = MemoryVfs(
	mapOf(name to this)
)[name]

suspend fun AsyncStream.readAllAsFastStream(offset: Int = 0) = this.readAll().openFastStream()

inline fun AsyncStream.getWrittenRange(callback: () -> Unit): LongRange {
	val start = position
	callback()
	val end = position
	return start until end
}

// Missing methods from Korio's AsyncStream
suspend fun AsyncStream.writeU_VL(value: Int) =
	this.apply { writeBytes(MemorySyncStreamToByteArray { writeU_VL(value) }) }

suspend fun AsyncStream.writeStringVL(str: String, charset: Charset = UTF8) =
	this.apply { writeBytes(MemorySyncStreamToByteArray { writeStringVL(str, charset) }) }

fun AsyncInputStream.withLength(length: Long): AsyncInputStream {
	val base = this
	var currentPos = 0L
	return object : AsyncInputStream by base, AsyncGetLengthStream, AsyncGetPositionStream {
		override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
			val read = base.read(buffer, offset, len)
			if (read >= 0) currentPos += read
			return read
		}

		override suspend fun getPosition(): Long = currentPos
		override suspend fun getLength(): Long = length
	}
}

fun MemoryAsyncStream(data: korlibs.memory.ByteArrayBuilder): AsyncStream = MemoryAsyncStreamBase(data).toAsyncStream()
fun MemoryAsyncStream(initialCapacity: Int = 4096): AsyncStream = MemoryAsyncStreamBase(initialCapacity).toAsyncStream()

/**
 * Creates a an [AsyncInputStream] from a [process] function that writes to a [AsyncOutputStream].
 *
 * The [process] function is executed lazily when the data is tried to be read.
 */
suspend fun asyncStreamWriter(bufferSize: Int = AsyncByteArrayDequeChunked.DEFAULT_MAX_SIZE, name: String? = null, lazy: Boolean = false, process: suspend (out: AsyncOutputStream) -> Unit): AsyncInputStream {
	return object : AsyncInputStream {
        val deque = when {
            lazy -> AsyncRingBuffer(bufferSize).also { it.name = name }
            else -> AsyncRingBufferChunked(bufferSize).also { it.name = name }
        }
        var lastError: Throwable? = null

        var job: Job? = null

        private fun checkException() {
            if (lastError != null) throw RuntimeException("Error in asyncStreamWriter", lastError!!)
        }

        private val temp = ByteArray(1)

        private suspend fun ensureJob() {
            if (job != null) return
            job = launchImmediately(coroutineContext) {
                try {
                    process(object : AsyncOutputStream {
                        override suspend fun write(buffer: ByteArray, offset: Int, len: Int) = deque.write(buffer, offset, len)
                        override suspend fun write(byte: Int) = deque.write(byte)
                        override suspend fun close() = deque.close()
                    })
                } catch (e: Throwable) {
                    lastError = e
                    e.printStackTrace()
                } finally {
                    deque.close()
                }
            }
        }

		override suspend fun read(buffer: ByteArray, offset: Int, len: Int): Int {
            ensureJob()
            //println("asyncStreamWriter[$deque].read($len)")
            checkException()
            return deque.read(buffer, offset, len).also {
                //println("/asyncStreamWriter[$deque].read($len) -> $it")
            }
        }
		override suspend fun read(): Int {
            return if (read(temp, 0, 1) > 0) {
                temp[0].toInt() and 0xFF
            } else {
                -1
            }
        }
		override suspend fun close() {
            //println("asyncStreamWriter[$deque].close")
            job?.cancel()
            job = null
        }
	}
}

suspend inline fun AsyncOutputStream.writeSync(hintSize: Int = 4096, callback: SyncStream.() -> Unit) {
	writeBytes(MemorySyncStreamToByteArray(hintSize) {
		callback()
	})
}

fun AsyncStreamBase.toSyncOrNull(): SyncStreamBase? = (this as? SyncAsyncStreamBase?)?.sync
    ?: (this as? MemoryAsyncStreamBase?)?.let { MemorySyncStreamBase(it.data) }

fun AsyncStream.toSyncOrNull(): SyncStream? = this.base.toSyncOrNull()?.let { SyncStream(it, this.position) }

fun SyncStream.toAsync(dispatcher: CoroutineDispatcher? = null): AsyncStream = this.base.toAsync(dispatcher).toAsyncStream(this.position)
fun SyncStreamBase.toAsync(dispatcher: CoroutineDispatcher? = null): AsyncStreamBase = when (this) {
	is MemorySyncStreamBase -> MemoryAsyncStreamBase(this.data)
	else -> SyncAsyncStreamBase(this, dispatcher)
}

@Deprecated("", ReplaceWith("toAsync()"))
fun SyncStream.toAsyncInWorker(): AsyncStream = toAsync()
@Deprecated("", ReplaceWith("toAsync()"))
fun SyncStreamBase.toAsyncInWorker(): AsyncStreamBase = toAsync()


class MemoryAsyncStreamBase(var data: ByteArrayBuilder) : AsyncStreamBase() {
	constructor(initialCapacity: Int = 4096) : this(ByteArrayBuilder(initialCapacity))

	var ilength: Int
		get() = data.size
		set(value) { data.size = value }

	override suspend fun setLength(value: Long) { ilength = value.toInt() }
	override suspend fun getLength(): Long = ilength.toLong()

	fun checkPosition(position: Long) { if (position < 0) invalidOp("Invalid position $position") }

	override suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
		checkPosition(position)
		if (position !in 0 until ilength) return 0
		val end = min(this.ilength.toLong(), position + len)
		val actualLen = max((end - position).toInt(), 0)
		arraycopy(this.data.data, position.toInt(), buffer, offset, actualLen)
		return actualLen
	}

	override suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) {
		checkPosition(position)
		data.size = max(data.size, (position + len).toInt())
		arraycopy(buffer, offset, this.data.data, position.toInt(), len)
	}

	override suspend fun close() = Unit

	override fun toString(): String = "MemoryAsyncStreamBase(${data.size})"
}
