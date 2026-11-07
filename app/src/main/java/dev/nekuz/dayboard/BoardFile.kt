package dev.nekuz.dayboard

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object BoundedIo {
    private const val BUFFER = 16 * 1024

    fun read(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

object AtomicFiles {
    fun write(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            temp.delete()
            throw IOException(e)
        }
    }
}

sealed class BoardLoad {
    data class Ok(val board: Board) : BoardLoad()

    data object Missing : BoardLoad()

    data class SetAside(val name: String) : BoardLoad()

    data object Blocked : BoardLoad()
}

class BoardFile(private val directory: File) {
    private val main = File(directory, MAIN_NAME)
    private var blocked = false

    fun load(nowMillis: Long): BoardLoad {
        directory.mkdirs()
        if (!main.exists()) return BoardLoad.Missing
        val bytes = try {
            main.inputStream().use { BoundedIo.read(it, Limits.MAX_FILE_BYTES) }
        } catch (e: IOException) {
            blocked = true
            return BoardLoad.Blocked
        } catch (e: SecurityException) {
            blocked = true
            return BoardLoad.Blocked
        }
        val decoded = bytes?.let(BoundedIo::utf8)?.let(BoardCodec::decode)
        if (decoded is Decoded.Ok) return BoardLoad.Ok(decoded.board)
        val name = setAside(nowMillis)
        if (name == null) {
            blocked = true
            return BoardLoad.Blocked
        }
        return BoardLoad.SetAside(name)
    }

    fun save(board: Board): Boolean {
        if (blocked) return false
        val text = BoardCodec.encode(board)
        if (BoardCodec.decode(text) !is Decoded.Ok) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Limits.MAX_FILE_BYTES) return false
        return try {
            directory.mkdirs()
            AtomicFiles.write(main, bytes)
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun corruptNames(): List<String> =
        (directory.list() ?: emptyArray()).filter(::isCorruptName).sorted()

    fun corruptFile(name: String): File? =
        if (isCorruptName(name) && name in corruptNames()) File(directory, name) else null

    fun deleteCorrupt(name: String): Boolean = corruptFile(name)?.delete() ?: false

    private fun setAside(nowMillis: Long): String? {
        var attempt = 0
        while (attempt < MAX_ASIDE_ATTEMPTS) {
            val name = "$CORRUPT_PREFIX$nowMillis-$attempt$CORRUPT_SUFFIX"
            val target = File(directory, name)
            if (!target.exists() && main.renameTo(target)) return name
            attempt += 1
        }
        return null
    }

    companion object {
        const val MAIN_NAME = "board.json"
        private const val CORRUPT_PREFIX = "board-corrupt-"
        private const val CORRUPT_SUFFIX = ".json"
        private const val MAX_ASIDE_ATTEMPTS = 100

        fun isCorruptName(name: String) =
            name.startsWith(CORRUPT_PREFIX) && name.endsWith(CORRUPT_SUFFIX) && '/' !in name && '\\' !in name
    }
}
