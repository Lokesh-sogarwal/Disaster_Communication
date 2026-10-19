package com.example.disasterconnect.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory


/**
 * Static checks of the backup configuration files. Whether Android actually honours them
 * during a real backup/restore can only be checked on a device.
 */
class BackupRulesTest {

    // JVM unit tests run with the app module as the working directory.
    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path)).documentElement

    private val identityFiles = setOf("disaster_connect_ble.xml", "${DeviceIdentity.PREFERENCES}.xml")

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    /** Excluded sharedpref files, after checking the section has no <include> (which would turn it into an allow-list). */
    private fun excludedPreferences(section: Element): Set<String> {
        assertTrue("${section.tagName} must not use <include>", section.children("include").isEmpty())
        return section.children("exclude")
            .onEach { assertEquals("sharedpref", it.getAttribute("domain")) }
            .map { it.getAttribute("path") }
            .toSet()
    }


    @Test
    fun manifestPointsAtBothBackupRuleFiles() {

        val application = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"

        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
    }


    @Test
    fun legacyAutoBackupExcludesOnlyTheIdentityPreferenceFiles() {

        assertEquals(identityFiles, excludedPreferences(xml("src/main/res/xml/backup_rules.xml")))
    }


    @Test
    fun cloudBackupAndDeviceTransferExcludeOnlyTheIdentityPreferenceFiles() {

        val rules = xml("src/main/res/xml/data_extraction_rules.xml")

        assertEquals(identityFiles, excludedPreferences(rules.children("cloud-backup").single()))
        assertEquals(identityFiles, excludedPreferences(rules.children("device-transfer").single()))
    }
}
