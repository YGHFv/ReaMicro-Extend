package com.reamicro.fix.cloud.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsBackupSecurityTest {
    @Test
    fun `normal backup excludes online account secrets`() {
        assertTrue(isSensitivePreference("online_login_password_source"))
        assertTrue(isSensitivePreference("online_login_cookie_source"))
        assertTrue(isSensitivePreference("online_source_variable_source"))
        assertFalse(isSensitivePreference("reader_highlight_enabled"))
    }
}
