package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class HostScanOverlayTest {
    @Test fun verifiedReverseIdentityAndAliasesWin() {
        val base = HostProfile.parse(
            "profile=known\nrequiredClass=Main\nclass=Book=b\ntypeAlias=Role=b\nmethod=Book#read=r\n",
        )
        val scan = HostProfile.parse(
            "profile=automatic\nrequiredClass=Main\nclass=Role=b\nclass=Book=c\nclass=New=n\n" +
                "typeAlias=Role=c\nmethod=Book#read=x\nmethod=New#load=l\n",
        )
        val merged = HostScanOverlay.merge(base, scan)
        assertEquals("Book", merged.logicalClassOf("b"))
        assertEquals("b", merged.classOf("Role"))
        assertEquals("r", merged.methodOf("Book", "read"))
        assertEquals("n", merged.classOf("New"))
        assertEquals("l", merged.methodOf("New", "load"))
        assertEquals("known", merged.fingerprint.id)
    }

    @Test fun existingAliasCannotBecomePrimaryClass() {
        val base = HostProfile.parse("profile=known\nrequiredClass=Main\ntypeAlias=Role=x\n")
        val scan = HostProfile.parse("profile=automatic\nrequiredClass=Main\nclass=Role=x\n")
        val merged = HostScanOverlay.merge(base, scan)
        assertFalse(merged.classes.containsKey("Role"))
        assertEquals("x", merged.typeAliases["Role"])
    }
}
