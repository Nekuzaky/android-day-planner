package dev.nekuz.dayboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class BoardFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val board = Board(nextId = 2, tasks = listOf(Task(1, "One", d("2026-01-01"))))

    private fun dir() = folder.root
    private fun main() = File(dir(), BoardFile.MAIN_NAME)

    @Test
    fun missingFileLoadsAsMissing() {
        assertEquals(BoardLoad.Missing, BoardFile(dir()).load(1))
    }

    @Test
    fun saveThenLoadRoundTrips() {
        assertTrue(BoardFile(dir()).save(board))
        assertEquals(BoardLoad.Ok(board), BoardFile(dir()).load(1))
    }

    @Test
    fun saveCreatesMissingDirectory() {
        val nested = File(dir(), "a/b")
        assertTrue(BoardFile(nested).save(board))
        assertEquals(BoardLoad.Ok(board), BoardFile(nested).load(1))
    }

    @Test
    fun saveLeavesNoTempFile() {
        BoardFile(dir()).save(board)
        assertFalse(File(dir(), BoardFile.MAIN_NAME + ".tmp").exists())
    }

    @Test
    fun saveOverwritesPrevious() {
        val file = BoardFile(dir())
        file.save(board)
        val next = board.copy(nextId = 3)
        file.save(next)
        assertEquals(BoardLoad.Ok(next), BoardFile(dir()).load(1))
    }

    @Test
    fun leftoverTempFileIsIgnoredAndReplaced() {
        File(dir(), BoardFile.MAIN_NAME + ".tmp").writeText("half written")
        val file = BoardFile(dir())
        assertEquals(BoardLoad.Missing, file.load(1))
        assertTrue(file.save(board))
        assertEquals(BoardLoad.Ok(board), BoardFile(dir()).load(1))
    }

    @Test
    fun interruptedWriteKeepsMainIntact() {
        BoardFile(dir()).save(board)
        File(dir(), BoardFile.MAIN_NAME + ".tmp").writeText("{\"format\":\"dayb")
        assertEquals(BoardLoad.Ok(board), BoardFile(dir()).load(1))
    }

    private fun assertSetAside(content: ByteArray) {
        main().writeBytes(content)
        val file = BoardFile(dir())
        val load = file.load(1234)
        assertTrue(load is BoardLoad.SetAside)
        val name = (load as BoardLoad.SetAside).name
        assertFalse(main().exists())
        assertArrayEquals(content, File(dir(), name).readBytes())
        assertEquals(listOf(name), file.corruptNames())
    }

    @Test
    fun garbageIsKeptAside() = assertSetAside("not json".toByteArray())

    @Test
    fun emptyFileIsKeptAside() = assertSetAside(ByteArray(0))

    @Test
    fun truncatedJsonIsKeptAside() {
        val text = BoardCodec.encode(board)
        assertSetAside(text.substring(0, text.length / 2).toByteArray())
    }

    @Test
    fun invalidUtf8IsKeptAside() = assertSetAside(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()))

    @Test
    fun invalidSchemaIsKeptAside() = assertSetAside("{\"format\":\"other\"}".toByteArray())

    @Test
    fun oversizedFileIsKeptAside() = assertSetAside(ByteArray(Limits.MAX_FILE_BYTES + 1) { 'x'.code.toByte() })

    @Test
    fun manyEmptyArraysAreKeptAside() {
        val text = "[" + List(2_000_000) { "[]" }.joinToString(",") + "]"
        assertSetAside(text.toByteArray())
    }

    @Test
    fun saveAfterCorruptionDoesNotTouchAsideCopy() {
        main().writeText("broken")
        val file = BoardFile(dir())
        val name = (file.load(1) as BoardLoad.SetAside).name
        assertTrue(file.save(board))
        assertEquals("broken", File(dir(), name).readText())
        assertEquals(BoardLoad.Ok(board), BoardFile(dir()).load(2))
        assertEquals(listOf(name), BoardFile(dir()).corruptNames())
    }

    @Test
    fun repeatedCorruptionAtSameTimeKeepsBothCopies() {
        main().writeText("first")
        val file = BoardFile(dir())
        file.load(5)
        main().writeText("second")
        BoardFile(dir()).load(5)
        assertEquals(2, file.corruptNames().size)
        assertEquals(setOf("first", "second"), file.corruptNames().map { File(dir(), it).readText() }.toSet())
    }

    @Test
    fun unreadableMainBlocksSavesAndIsNotOverwritten() {
        assertTrue(main().mkdirs())
        val file = BoardFile(dir())
        assertEquals(BoardLoad.Blocked, file.load(1))
        assertFalse(file.save(board))
        assertTrue(main().isDirectory)
    }

    @Test
    fun corruptFileLookupRefusesOtherNames() {
        val file = BoardFile(dir())
        file.save(board)
        assertNull(file.corruptFile(BoardFile.MAIN_NAME))
        assertNull(file.corruptFile("../board.json"))
        assertNull(file.corruptFile("board-corrupt-../../x.json"))
        assertNull(file.corruptFile("board-corrupt-1-0.json"))
        assertFalse(file.deleteCorrupt(BoardFile.MAIN_NAME))
        assertTrue(main().exists())
    }

    @Test
    fun deleteCorruptRemovesOnlyThatFile() {
        main().writeText("broken")
        val file = BoardFile(dir())
        val name = (file.load(1) as BoardLoad.SetAside).name
        file.save(board)
        assertTrue(file.deleteCorrupt(name))
        assertTrue(file.corruptNames().isEmpty())
        assertTrue(main().exists())
    }

    @Test
    fun corruptNamePattern() {
        assertTrue(BoardFile.isCorruptName("board-corrupt-1-0.json"))
        assertFalse(BoardFile.isCorruptName("board.json"))
        assertFalse(BoardFile.isCorruptName("alarms.json"))
        assertFalse(BoardFile.isCorruptName("board-corrupt-/x.json"))
        assertFalse(BoardFile.isCorruptName("board-corrupt-\\x.json"))
    }

    @Test
    fun atomicWriteReplacesExisting() {
        val target = File(dir(), "x.txt")
        target.writeText("old")
        AtomicFiles.write(target, "new".toByteArray())
        assertEquals("new", target.readText())
    }

    @Test
    fun boundedReadAtLimit() {
        assertEquals(10, BoundedIo.read(ByteArrayInputStream(ByteArray(10)), 10)?.size)
    }

    @Test
    fun boundedReadOverLimit() {
        assertNull(BoundedIo.read(ByteArrayInputStream(ByteArray(11)), 10))
    }

    @Test
    fun boundedReadOfEndlessStreamStops() {
        val endless = object : java.io.InputStream() {
            override fun read() = 0
        }
        assertNull(BoundedIo.read(endless, 1000))
    }

    @Test
    fun strictUtf8() {
        assertEquals("é", BoundedIo.utf8("é".toByteArray()))
        assertNull(BoundedIo.utf8(byteArrayOf(0xC3.toByte())))
        assertNull(BoundedIo.utf8(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())))
    }
}
