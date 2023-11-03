package org.jetbrains.kotlin.benchmarks

import kotlinx.benchmark.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.math.ceil
import kotlin.random.Random

val asciiString = mapOf(
    100 to createString("ASCII", 100),
    10_000 to createString("ASCII", 10_000),
    1_000_000 to createString("ASCII", 1_000_000),
    100_000_000 to createString("ASCII", 100_000_000)
)

val unicodeString = mapOf(
    100 to createString("Unicode", 100),
    10_000 to createString("Unicode", 10_000),
    1_000_000 to createString("Unicode", 1_000_000),
    100_000_000 to createString("Unicode", 100_000_000)
)

private fun createString(type: String, length: Int): String = buildString {
    when (type) {
        "ASCII" -> {
            repeat(length) {
                append(Random.nextInt(128).toChar())
            }
        }
        "Unicode" -> {
            repeat(length) {
                append(Random.nextInt().toChar())
            }
        }
        else -> throw UnsupportedOperationException("Unknown type: $type")
    }
}


@State(Scope.Benchmark)
open class FileWriteBench {
    @Param("100", "10000", "1000000", "100000000") // the last are 1M & 100M
    var length: Int = 0

    @Param("ASCII", "Unicode")
    var type: String = ""

    @Param("UTF-8", "UTF-16", "UTF-32", "US-ASCII", "ISO-8859-1")
    var charsetName: String = ""

    private var charset: Charset = Charsets.UTF_8
    private var string: String = ""

    private val file = File.createTempFile("bench", ".tmp")
    private val path = file.toPath()

    @Setup
    fun setUp() {
        charset = Charset.forName(charsetName)

        string = when (type) {
            "ASCII" -> {
                asciiString[this@FileWriteBench.length]!!
            }
            "Unicode" -> {
                unicodeString[this@FileWriteBench.length]!!
            }
            else -> throw UnsupportedOperationException("Unknown type: $type")
        }
    }

    @Benchmark
    fun originalWrite() {
        file.writeText(string, charset)
    }

    @Benchmark
    fun originalAppend() {
        file.writeText("_")
        file.appendText(string, charset)
    }

    @Benchmark
    fun bufferedWrite() {
        file.bufferedWrite(string, charset)
    }

    @Benchmark
    fun bufferedAppend() {
        file.writeText("_")
        file.bufferedAppend(string, charset)
    }

    @Benchmark
    fun manualBufferedWrite() {
        file.manualBufferedWrite(string, charset)
    }

    @Benchmark
    fun manualBufferedAppend() {
        file.writeText("_")
        file.manualBufferedAppend(string, charset)
    }

    @Benchmark
    fun manualBufferedWriteWrap() {
        file.manualBufferedWriteWrap(string, charset)
    }

    @Benchmark
    fun manualBufferedAppendWrap() {
        file.manualBufferedAppendWrap(string, charset)
    }

    // Does not fit the goal, throws on invalid sequence
    @Benchmark
    fun jdk11Write() {
        Files.writeString(file.toPath(), string, charset)
    }

    // Does not fit the goal, throws on invalid sequence
    @Benchmark
    fun jdk11Append() {
        file.writeText("_")
        Files.writeString(file.toPath(), string, charset, StandardOpenOption.APPEND)
    }

    @Benchmark
    fun pathOriginalWrite() {
        path.writeText(string, charset)
    }

    @Benchmark
    fun pathOriginalAppend() {
        path.appendText(string, charset)
    }

    @Benchmark
    fun manualEncodeCopy(): Int {
        return manualEncodeCopyImpl(string, charset)
    }

    @Benchmark
    fun manualEncodeWrap(): Int {
        return manualEncodeWrapImpl(string, charset)
    }
}

private const val chunkSize = DEFAULT_BUFFER_SIZE

private fun File.bufferedWrite(text: String, charset: Charset): Unit {
    bufferedWriter(charset, chunkSize).use {
        it.write(text)
    }
}

private fun File.bufferedAppend(text: String, charset: Charset): Unit {
    FileOutputStream(this, true).writer(charset).buffered(chunkSize).use {
        it.write(text)
    }
}

private fun File.manualBufferedWrite(text: String, charset: Charset): Unit =
    FileOutputStream(this).use { it.writeTextImpl(text, charset) }

private fun File.manualBufferedAppend(text: String, charset: Charset): Unit =
    FileOutputStream(this, true).use { it.writeTextImpl(text, charset) }

private fun File.manualBufferedWriteWrap(text: String, charset: Charset): Unit =
    FileOutputStream(this).use { it.writeTextWrap(text, charset) }

private fun File.manualBufferedAppendWrap(text: String, charset: Charset): Unit =
    FileOutputStream(this, true).use { it.writeTextWrap(text, charset) }

private fun Charset.newReplaceEncoder() = newEncoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.REPLACE)

private fun byteBufferForEncoding(chunkSize: Int, encoder: CharsetEncoder): ByteBuffer {
    val maxBytesPerChar = ceil(encoder.maxBytesPerChar()).toInt() // including replacement sequence
    return ByteBuffer.allocate(chunkSize * maxBytesPerChar)
}

private fun OutputStream.writeTextImpl(text: String, charset: Charset): Unit {
    val encoder = charset.newReplaceEncoder()
    val charBuffer = CharBuffer.allocate(chunkSize)
    val byteBuffer = byteBufferForEncoding(chunkSize, encoder)

    var startIndex = 0
    var leftover = 0

    while (startIndex < text.length) {
        val copyLength = minOf(chunkSize - leftover, text.length - startIndex)
        val endIndex = startIndex + copyLength

        text.toCharArray(charBuffer.array(), leftover, startIndex, endIndex)
        charBuffer.limit(copyLength + leftover)
        encoder.encode(charBuffer, byteBuffer, /*endOfInput = */endIndex == text.length).also { check(it.isUnderflow) }
        this.write(byteBuffer.array(), 0, byteBuffer.position())

        if (charBuffer.position() != charBuffer.limit()) {
            charBuffer.put(0, charBuffer.get()) // the last char is a high surrogate
            leftover = 1
        } else {
            leftover = 0
        }

        charBuffer.clear()
        byteBuffer.clear()
        startIndex = endIndex
    }
}

private fun manualEncodeCopyImpl(text: String, charset: Charset): Int {
    val encoder = charset.newReplaceEncoder()
    val charBuffer = CharBuffer.allocate(chunkSize)
    val byteBuffer = byteBufferForEncoding(chunkSize, encoder)

    var startIndex = 0
    var leftover = 0

    var result = 0
    while (startIndex < text.length) {
        val copyLength = minOf(chunkSize - leftover, text.length - startIndex)
        val endIndex = startIndex + copyLength

        text.toCharArray(charBuffer.array(), leftover, startIndex, endIndex)
        charBuffer.limit(copyLength + leftover)
        encoder.encode(charBuffer, byteBuffer, /*endOfInput = */endIndex == text.length).also { check(it.isUnderflow) }
        result += byteBuffer.position()

        if (charBuffer.position() != charBuffer.limit()) {
            charBuffer.put(0, charBuffer.get()) // the last char is a high surrogate
            leftover = 1
        } else {
            leftover = 0
        }

        charBuffer.clear()
        byteBuffer.clear()
        startIndex = endIndex
    }
    return result
}

private fun manualEncodeWrapImpl(text: String, charset: Charset): Int {
    val encoder = charset.newReplaceEncoder()
    val charBuffer = CharBuffer.wrap(text)
    val byteBuffer = byteBufferForEncoding(chunkSize, encoder)

    var result = 0
    while (charBuffer.hasRemaining()) {
        encoder.encode(charBuffer, byteBuffer, /*endOfInput = */true)
        result += byteBuffer.position()
        byteBuffer.clear()
    }
    return result
}

private fun OutputStream.writeTextWrap(text: String, charset: Charset): Unit {
    val encoder = charset.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    val charBuffer = CharBuffer.wrap(text)

    val maxBytesPerChar = ceil(encoder.maxBytesPerChar()).toInt() // including replacement sequence
    val byteBuffer = ByteBuffer.allocate(chunkSize * maxBytesPerChar)

    while (charBuffer.hasRemaining()) {
        encoder.encode(charBuffer, byteBuffer, /*endOfInput = */true)
        this.write(byteBuffer.array(), 0, byteBuffer.position())
        byteBuffer.clear()
    }
}
