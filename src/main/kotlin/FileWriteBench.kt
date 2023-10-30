package org.jetbrains.kotlin.benchmarks

import kotlinx.benchmark.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
    fun newWrite() {
        file.newWriteText(string, charset)
    }

    @Benchmark
    fun newAppend() {
        file.writeText("_")
        file.newAppendText(string, charset)
    }

    @Benchmark
    fun manualWrite() {
        file.manualWriteText(string, charset)
    }

    @Benchmark
    fun manualAppend() {
        file.writeText("_")
        file.manualAppendText(string, charset)
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
}

private const val chunkSize = DEFAULT_BUFFER_SIZE

private fun File.newWriteText(text: String, charset: Charset = Charsets.UTF_8): Unit {
    if (text.length < 5 * chunkSize) {
        writeBytes(text.toByteArray(charset))
        return
    }
    bufferedWriter(charset, chunkSize).use {
        it.write(text)
    }
}

private fun File.newAppendText(text: String, charset: Charset = Charsets.UTF_8): Unit {
    if (text.length < 5 * chunkSize) {
        appendBytes(text.toByteArray(charset))
        return
    }
    FileOutputStream(this, true).writer(charset).buffered(chunkSize).use {
        it.write(text)
    }
}

private fun File.manualWriteText(text: String, charset: Charset = Charsets.UTF_8): Unit =
    FileOutputStream(this).use { it.writeText(text, charset) }

private fun File.manualAppendText(text: String, charset: Charset = Charsets.UTF_8): Unit =
    FileOutputStream(this, true).use { it.writeText(text, charset) }

private fun FileOutputStream.writeText(text: String, charset: Charset = Charsets.UTF_8): Unit {
    if (text.length < 5 * chunkSize) {
        this.write(text.toByteArray(charset))
        return
    }

    val encoder = charset.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    val charBuffer = CharBuffer.allocate(chunkSize)

    val maxBytesPerChar = ceil(encoder.maxBytesPerChar()).toInt() // including replacement sequence
    val byteBuffer = ByteBuffer.allocate(chunkSize * maxBytesPerChar)

    var startIndex = 0
    var leftover = 0

    while (startIndex < text.length) {
        val endIndex = (startIndex + chunkSize - leftover).coerceAtMost(text.length)
        val endOfInput = endIndex == text.length

        text.toCharArray(charBuffer.array(), leftover, startIndex, endIndex)
        charBuffer.limit(endIndex - startIndex + leftover)
        val coderResult = encoder.encode(charBuffer, byteBuffer, endOfInput)
        check(coderResult.isUnderflow)
        this.write(byteBuffer.array(), 0, byteBuffer.position())

        if (charBuffer.position() != charBuffer.limit()) {
            charBuffer.put(0, charBuffer.get()) // the last char is a high surrogate
            leftover = 1
        } else {
            leftover = 0
        }

        startIndex = endIndex
        charBuffer.clear()
        byteBuffer.clear()
    }
}
