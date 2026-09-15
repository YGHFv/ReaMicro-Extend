package com.reamicro.fix.cloud.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalTaskManifestTest {
    @Test
    fun `network constrained jobs declare permission and a private system bound service`() {
        val path = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(path)
        val namespace = "http://schemas.android.com/apk/res/android"
        val permissionNodes = document.getElementsByTagName("uses-permission")
        val permissions = (0 until permissionNodes.length).map { (permissionNodes.item(it) as Element).getAttributeNS(namespace, "name") }
        assertTrue("android.permission.ACCESS_NETWORK_STATE" in permissions)
        val services = document.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(namespace, "name") == ".cloud.local.LocalTaskJobService" }
        assertEquals("false", service.getAttributeNS(namespace, "exported"))
        assertEquals("android.permission.BIND_JOB_SERVICE", service.getAttributeNS(namespace, "permission"))
    }
}
