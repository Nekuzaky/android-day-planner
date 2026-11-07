package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun projectFile(path: String): File =
        listOf(File(path), File("app", path)).first { it.exists() }

    private fun parse(path: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(projectFile(path))
    }

    private val manifest by lazy { parse("src/main/AndroidManifest.xml") }

    private fun elements(document: Document, name: String): List<Element> {
        val list = document.getElementsByTagName(name)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.attr(name: String) = getAttributeNS(android, name)

    private fun component(tag: String, name: String) = elements(manifest, tag).single { it.attr("name") == name }

    private fun actions(element: Element): List<String> {
        val list = element.getElementsByTagName("action")
        return (0 until list.length).map { (list.item(it) as Element).attr("name") }
    }

    @Test
    fun permissionsAreExactlyTheThreeNeeded() {
        assertEquals(
            setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.SCHEDULE_EXACT_ALARM",
            ),
            elements(manifest, "uses-permission").map { it.attr("name") }.toSet(),
        )
    }

    @Test
    fun noInternetPermission() {
        assertFalse(elements(manifest, "uses-permission").any { it.attr("name") == "android.permission.INTERNET" })
    }

    @Test
    fun backupIsOff() {
        val application = elements(manifest, "application").single()
        assertEquals("false", application.attr("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.attr("dataExtractionRules"))
    }

    @Test
    fun onlyLauncherActivityIsExported() {
        val activities = elements(manifest, "activity")
        assertEquals(4, activities.size)
        activities.forEach {
            val expected = if (it.attr("name") == ".MainActivity") "true" else "false"
            assertEquals(it.attr("name"), expected, it.attr("exported"))
        }
    }

    @Test
    fun launcherFilterIsOnlyMainLauncher() {
        val main = component("activity", ".MainActivity")
        assertEquals(listOf("android.intent.action.MAIN"), actions(main))
        assertEquals(0, main.getElementsByTagName("data").length)
    }

    @Test
    fun internalActivitiesHaveNoIntentFilter() {
        listOf(".TaskEditActivity", ".HabitEditActivity", ".SlotEditActivity").forEach {
            assertEquals(it, 0, component("activity", it).getElementsByTagName("intent-filter").length)
        }
    }

    @Test
    fun systemEventReceiverIsNotExportedAndListensToExactlyTheSystemActions() {
        val receiver = component("receiver", ".SystemEventReceiver")
        assertEquals("false", receiver.attr("exported"))
        assertEquals(SystemEvents.ACCEPTED, actions(receiver).toSet())
        assertEquals(5, actions(receiver).size)
        assertEquals(0, receiver.getElementsByTagName("data").length)
    }

    @Test
    fun alarmReceiverIsNotExportedAndHasNoFilter() {
        val receiver = component("receiver", ".AlarmReceiver")
        assertEquals("false", receiver.attr("exported"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun exactlyTwoReceiversAndNoOtherComponents() {
        assertEquals(2, elements(manifest, "receiver").size)
        assertEquals(0, elements(manifest, "service").size)
        assertEquals(0, elements(manifest, "provider").size)
    }

    @Test
    fun receiverSourceChecksActionBeforeAnyWork() {
        val source = projectFile("src/main/java/dev/nekuz/dayboard/Receivers.kt").readText()
        val body = source.substringAfter("class SystemEventReceiver").substringBefore("class AlarmReceiver")
        assertTrue(body.indexOf("isAccepted") in 0 until body.indexOf("goAsync"))
        assertFalse(body.contains("Extra"))
        assertFalse(body.contains("getData"))
        assertFalse(body.contains(".data"))
    }

    @Test
    fun alarmReceiverOnlyReadsTheTaskId() {
        val source = projectFile("src/main/java/dev/nekuz/dayboard/Receivers.kt").readText()
        val body = source.substringAfter("class AlarmReceiver")
        assertEquals(1, Regex("get[A-Za-z]*Extra").findAll(body).count())
        assertTrue(body.contains("validId"))
    }

    @Test
    fun alarmPendingIntentsAreImmutable() {
        val source = projectFile("src/main/java/dev/nekuz/dayboard/Alarms.kt").readText()
        val notification = projectFile("src/main/java/dev/nekuz/dayboard/Notifications.kt").readText()
        (source + notification).split("PendingIntent.get").drop(1).forEach {
            assertTrue(it.substringBefore("\n        )").contains("FLAG_IMMUTABLE"))
        }
        assertFalse(source.contains("FLAG_MUTABLE"))
    }

    @Test
    fun documentIoNeverRunsOnTheStoreExecutor() {
        val source = projectFile("src/main/java/dev/nekuz/dayboard/Store.kt").readText()
        assertEquals(2, Regex("contentResolver").findAll(source).count())
        val calls = Regex("= (parseImport|writeDocument)\\(app|&& writeDocument\\(app").findAll(source).toList()
        assertEquals(3, calls.size)
        calls.forEach { call ->
            val before = source.substring(0, call.range.first)
            assertTrue(before.lastIndexOf("io.execute") > before.lastIndexOf("    fun "))
        }
    }

    @Test
    fun launcherReadsNothingFromItsIntent() {
        val source = projectFile("src/main/java/dev/nekuz/dayboard/MainActivity.kt").readText()
        assertFalse(Regex("(?<![A-Za-z.])intent\\.").containsMatchIn(source.replace("val intent = Intent", "")))
        assertFalse(source.contains("getIntent"))
        assertFalse(source.contains("onNewIntent"))
    }

    @Test
    fun dataExtractionExcludesEverything() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        assertEquals(10, elements(rules, "exclude").size)
        assertEquals(0, elements(rules, "include").size)
    }

    @Test
    fun buttonStyleFiltersObscuredTouches() {
        val styles = parse("src/main/res/values/styles.xml")
        val base = elements(styles, "style").single { it.getAttribute("name") == "BaseButton" }
        val items = base.getElementsByTagName("item")
        val found = (0 until items.length).map { items.item(it) as Element }
            .single { it.getAttribute("name") == "android:filterTouchesWhenObscured" }
        assertEquals("true", found.textContent)
    }

    @Test
    fun everyButtonInLayoutsUsesAGuardedStyle() {
        val layouts = projectFile("src/main/res/layout").listFiles()!!.filter { it.extension == "xml" }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { file ->
            val document = parse("src/main/res/layout/${file.name}")
            elements(document, "Button").forEach {
                assertTrue(file.name, it.getAttribute("style").matches(Regex("@style/(TabButton|SecondaryButton|PrimaryButton)")))
            }
        }
    }

    @Test
    fun brandColourIsNotAlreadyUsedByAnotherApp() {
        val colors = parse("src/main/res/values/colors.xml")
        val start = elements(colors, "color").single { it.getAttribute("name") == "brand_start" }.textContent
        assertFalse(start in setOf("#4D7C0F", "#0B7F86", "#15803D", "#6D4AFF", "#0E7490", "#B45309", "#BE123C"))
    }
}
