package com.reamicro.fix.hook

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import com.reamicro.fix.core.HostAccountBindings
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.cloud.local.LocalTaskAccountBundle
import com.reamicro.fix.cloud.local.LocalTaskMirror
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.reader.ReaderTypographyPolicy
import com.reamicro.fix.xposed.HookRuntime
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

private const val LOG_PREFIX = "ReaMicro LSP"
private const val STORE_PREFS = "reamicro_fix_account_store"
private const val STORE_KEY_ACCOUNTS = "accounts"
private const val PORTABLE_PREFIX = "REAMICRO_ACCOUNT_1:"
private const val PORTABLE_FORMAT = 1
private const val HOST_DB_NAME = "app.db"
private const val PREF_KEYS_CLASS = HostClasses.Host.PREF_KEYS
private const val THIRD_PARTY_KEYS_CLASS = HostClasses.Host.THIRD_PARTY_KEYS
private const val WEBDAV_PREFS = "reamicro_fix_webdav"
private const val LOCAL_LIBRARY_PREFS = "reamicro_fix_local_library"
private const val MODULE_SETTINGS_PREFS = "reamicro_fix_module_settings"
private const val READER_AUTO_PAGE_PREFS = "reamicro_reader_auto_page"
private const val READER_PAGE_SPEED_PREFS = "reamicro_reader_page_speed"
private const val AI_API_CONFIG_FILE = "reamicro_ai_apis.json"
private const val AI_DICTIONARY_SETTINGS_FILE = "reamicro_dictionary_settings.json"
private const val AI_IMAGE_SETTINGS_FILE = "reamicro_image_settings.json"
private const val ONLINE_SOURCE_DIR = "reamicro_online_sources"
private const val ASSOCIATION_SOURCE_DIR = "reamicro_sources"
private const val WEBDAV_KEY_URL = "url"
private const val WEBDAV_KEY_USERNAME = "username"
private const val WEBDAV_KEY_PASSWORD = "password"
private const val WEBDAV_KEY_AUTHORIZED = "authorized"
private const val SUSPEND_WAIT_TIMEOUT_MS = 15_000L
private const val DATABASE_EXPORT_ID_CHUNK_SIZE = 400
private const val MAX_BUNDLE_METADATA_BYTES = 32 * 1024 * 1024
private const val LOCAL_TASK_BUNDLE_ENTRY = "account/local_tasks.json"

private val PORTABLE_WEBDAV_KEYS = setOf(
    WEBDAV_KEY_URL,
    WEBDAV_KEY_USERNAME,
    WEBDAV_KEY_PASSWORD,
    WEBDAV_KEY_AUTHORIZED,
)

private val MODULE_BACKUP_PREFS = setOf(
    MODULE_SETTINGS_PREFS,
    READER_AUTO_PAGE_PREFS,
    READER_PAGE_SPEED_PREFS,
)

private val MODULE_BACKUP_FILES = setOf(
    AI_API_CONFIG_FILE,
    AI_DICTIONARY_SETTINGS_FILE,
    AI_IMAGE_SETTINGS_FILE,
)

private val MODULE_BACKUP_DIRS = setOf(
    ONLINE_SOURCE_DIR,
    ASSOCIATION_SOURCE_DIR,
)

class AccountCompletionController(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
) {
    private val host get() = requireNotNull(HostAccountBindings.identity) { "账户动态查询不可用" }
    private val preferences get() = requireNotNull(HostAccountBindings.preferences) { "账户偏好动态查询不可用" }
    private val advancedSettings = AccountAdvancedSettings(
        classLoader,
        currentTarget = ::currentAccountTarget,
        memberAtCapture = {
            val current = activityProvider()?.let(::currentCachedUser)
            current != null && requireNotNull(HostAccountBindings.advanced).method("member").invoke(null, current) == true
        },
        dataStore = { activityProvider()?.let(::currentSession)?.let { preferences.field("store").get(it) } },
        preferenceKey = { key(PREF_KEYS_CLASS, it) },
        savedPreferences = { accountId ->
            activityProvider()?.let { activity ->
                loadStoredAccounts(activity).firstOrNull { it.accountId == accountId }?.advancedPreferences
            }
        },
        checkCurrentMember = {
            val expected = currentAccountTarget() ?: error("阅微账号正在更新，请稍后重试")
            val activity = activityProvider() ?: error("当前没有可用页面")
            val current = currentCachedUser(activity) ?: error("当前未登录阅微账号")
            val repository = AccountHostRuntime.repository(activity) ?: error("无法获取阅微账号仓库")
            check(host.field("user.id").getLong(current) == expected.accountId &&
                host.field("user.token").get(current) == expected.token) { "阅微账号已经变化，请重试切换" }
            val info = requireNotNull(host.method("info").invoke(current))
            invokeSuspendMethod(requireNotNull(HostAccountBindings.advanced).method("check.repository"), repository, info)
        },
    )

    internal fun installAdvancedSettingsRestore(isEnabled: () -> Boolean) {
        advancedSettings.install(isEnabled)
    }

    internal fun installNativeSignOut(isEnabled: () -> Boolean) {
        AccountNativeSignOut(
            classLoader,
            started = {
                synchronized(advancedSettings.operationLock) {
                    runCatching { if (isEnabled()) persistCurrentAccountSnapshot() }.onFailure {
                        HookRuntime.logError("$LOG_PREFIX failed to persist account before sign out", it)
                    }
                    advancedSettings.nativeSignOutStarted()
                    AccountHostRuntime.beginIdentityMutation()
                }
            },
            finished = {
                synchronized(advancedSettings.operationLock) {
                    AccountHostRuntime.endIdentityMutation()
                    advancedSettings.nativeSignOutFinished()
                }
            },
        ).install()
    }

    fun displayAccounts(): List<AccountDisplayItem> {
        val activity = activityProvider() ?: return emptyList()
        val current = currentAccountPreview(activity)
        val currentId = current?.accountId
        val merged = linkedMapOf<Long, StoredAccount>()
        loadStoredAccounts(activity).forEach { merged[it.accountId] = it }
        current?.let { preview ->
            val existing = merged[preview.accountId]
            merged[preview.accountId] = existing?.mergePreview(preview) ?: preview.toPlaceholderStoredAccount()
        }
        return merged.values
            .sortedWith(
                compareByDescending<StoredAccount> { it.accountId == currentId }
                    .thenByDescending { it.lastUsedAt },
            )
            .map { account ->
                AccountDisplayItem(
                    accountId = account.accountId,
                    nickname = account.nickname,
                    identity = displayIdentity(account.email, account.thirdBinds),
                    isCurrent = account.accountId == currentId,
                )
            }
    }

    fun exportCurrentCredential(): ExportedCredential {
        return synchronized(advancedSettings.operationLock) {
            val activity = activityProvider() ?: error("当前没有可用页面")
            val account = captureCurrentAccount(activity) ?: error("当前未登录阅微账号")
            val accounts = loadStoredAccounts(activity)
                .associateByTo(linkedMapOf()) { it.accountId }
            val normalized = account.copy(lastUsedAt = System.currentTimeMillis())
            accounts[normalized.accountId] = normalized
            saveStoredAccounts(activity, accounts.values.toList())
            return@synchronized ExportedCredential(
                account = AccountDisplayItem(
                    accountId = normalized.accountId,
                    nickname = normalized.nickname,
                    identity = displayIdentity(normalized.email, normalized.thirdBinds),
                    isCurrent = true,
                ),
                credential = encodePortableCredential(normalized.toPortableCredential()),
            )
        }
    }

    fun currentCloudCredential(): CurrentCloudCredential {
        val activity = activityProvider() ?: error("当前没有可用页面")
        val current = currentAccountPreview(activity) ?: error("当前未登录阅微账号")
        require(current.token.isNotBlank()) { "当前阅微登录密钥为空，请重新登录阅微" }
        return CurrentCloudCredential(
            accountId = current.accountId.toString(),
            token = current.token,
            label = current.nickname.ifBlank { "阅微账号 ${current.accountId}" },
        )
    }

    fun exportAllCredentialsBundle(): ExportedCredentialBundle {
        return synchronized(advancedSettings.operationLock) {
            val activity = activityProvider() ?: error("当前没有可用页面")
            val accounts = loadStoredAccounts(activity)
                .associateByTo(linkedMapOf()) { it.accountId }
            captureCurrentAccount(activity)?.copy(lastUsedAt = System.currentTimeMillis())?.let { snapshot ->
                accounts[snapshot.accountId] = snapshot
            }
            if (accounts.isEmpty()) error("当前没有可导出的账号")
            val sortedAccounts = accounts.values.sortedByDescending { it.lastUsedAt }
            saveStoredAccounts(activity, sortedAccounts)
            val accountArray = JSONArray()
            sortedAccounts.forEach { account ->
                accountArray.put(
                    JSONObject().apply {
                        put("accountId", account.accountId)
                        put("nickname", account.nickname)
                        put("identity", displayIdentity(account.email, account.thirdBinds))
                        put("credential", encodePortableCredential(account.toPortableCredential()))
                    },
                )
            }
            val payload = JSONObject().apply {
                put("format", PORTABLE_FORMAT)
                put("type", "credential_bundle")
                put("exportedAt", System.currentTimeMillis())
                put("count", sortedAccounts.size)
                put("accounts", accountArray)
            }
            return@synchronized ExportedCredentialBundle(
                count = sortedAccounts.size,
                content = payload.toString(2),
            )
        }
    }

    fun importCredential(raw: String): AccountDisplayItem {
        return synchronized(advancedSettings.operationLock) {
            val activity = activityProvider() ?: error("当前没有可用页面")
            val imported = decodePortableCredential(raw).copy(lastUsedAt = System.currentTimeMillis())
            val accounts = loadStoredAccounts(activity)
                .associateByTo(linkedMapOf()) { it.accountId }
            val existing = accounts[imported.accountId]
            accounts[imported.accountId] = existing?.mergeWith(imported) ?: imported
            saveStoredAccounts(activity, accounts.values.toList())
            val currentId = currentAccountPreview(activity)?.accountId
            return@synchronized AccountDisplayItem(
                accountId = imported.accountId,
                nickname = imported.nickname,
                identity = displayIdentity(imported.email, imported.thirdBinds),
                isCurrent = imported.accountId == currentId,
            )
        }
    }

    fun importCredentials(raw: String): ImportResult {
        return synchronized(advancedSettings.operationLock) {
            val activity = activityProvider() ?: error("当前没有可用页面")
            val importedAccounts = decodePortableCredentials(raw)
            if (importedAccounts.isEmpty()) error("未读取到可导入的账号凭证")
            val accounts = loadStoredAccounts(activity)
                .associateByTo(linkedMapOf()) { it.accountId }
            val normalizedAccounts = importedAccounts.mapIndexed { index, account ->
                account.copy(lastUsedAt = System.currentTimeMillis() + index)
            }
            normalizedAccounts.forEach { imported ->
                val existing = accounts[imported.accountId]
                accounts[imported.accountId] = existing?.mergeWith(imported) ?: imported
            }
            saveStoredAccounts(activity, accounts.values.toList())
            val currentId = currentAccountPreview(activity)?.accountId
            return@synchronized ImportResult(
                count = normalizedAccounts.size,
                accounts = normalizedAccounts.map { imported ->
                    AccountDisplayItem(
                        accountId = imported.accountId,
                        nickname = imported.nickname,
                        identity = displayIdentity(imported.email, imported.thirdBinds),
                        isCurrent = imported.accountId == currentId,
                    )
                },
            )
        }
    }

    fun switchToAccount(accountId: Long): AccountDisplayItem = synchronized(advancedSettings.operationLock) {
        advancedSettings.requireIdentityMutation()
        val activity = activityProvider() ?: error("当前没有可用页面")
        val current = currentAccountPreview(activity)
        if (current?.accountId == accountId) {
            return AccountDisplayItem(
                accountId = current.accountId,
                nickname = current.nickname,
                identity = displayIdentity(current.email, current.thirdBinds),
                isCurrent = true,
            )
        }
        val stored = loadStoredAccounts(activity)
            .associateByTo(linkedMapOf()) { it.accountId }
        captureCurrentAccount(activity)?.copy(lastUsedAt = System.currentTimeMillis())?.let { snapshot ->
            stored[snapshot.accountId] = snapshot
        }
        val target = stored[accountId] ?: error("未找到账号凭证")
        val normalizedTarget = target.copy(lastUsedAt = System.currentTimeMillis())
        // 身份写入触发宿主异步清理前，先持久保存切出账号的原始选择。
        saveStoredAccounts(activity, stored.values.toList())
        restoreAccount(activity, normalizedTarget)
        stored[accountId] = normalizedTarget
        saveStoredAccounts(activity, stored.values.toList())
        return AccountDisplayItem(
            accountId = accountId,
            nickname = normalizedTarget.nickname,
            identity = displayIdentity(normalizedTarget.email, normalizedTarget.thirdBinds),
            isCurrent = true,
        )
    }

    fun persistCurrentAccountSnapshot(): Boolean = synchronized(advancedSettings.operationLock) {
        val activity = activityProvider() ?: return false
        val snapshot = captureCurrentAccount(activity) ?: return false
        val stored = loadStoredAccounts(activity)
            .associateByTo(linkedMapOf()) { it.accountId }
        val normalized = snapshot.copy(lastUsedAt = System.currentTimeMillis())
        val existing = stored[normalized.accountId]
        stored[normalized.accountId] = existing?.mergeWith(normalized) ?: normalized
        saveStoredAccounts(activity, stored.values.toList())
        return true
    }

    fun exportAccountDataBundle(accountId: Long): ExportedAccountDataBundle {
        val activity = activityProvider() ?: error("当前没有可用页面")
        val account = resolveAccountForDataExport(activity, accountId)
        val fileName = buildAccountDataExportFileName(account.accountId)
        val bytes = ByteArrayOutputStream().use { output ->
            writeAccountDataZip(output.buffered(), activity, account)
            output.toByteArray()
        }
        return ExportedAccountDataBundle(
            account = AccountDisplayItem(
                accountId = account.accountId,
                nickname = account.nickname,
                identity = displayIdentity(account.email, account.thirdBinds),
                isCurrent = currentAccountPreview(activity)?.accountId == account.accountId,
            ),
            fileName = fileName,
            bytes = bytes,
        )
    }

    fun exportAllAccountDataBundles(): List<ExportedAccountDataBundle> {
        val accountIds = displayAccounts().map { it.accountId }.distinct()
        if (accountIds.isEmpty()) error("暂无可导出的账号")
        persistCurrentAccountSnapshot()
        return accountIds.map(::exportAccountDataBundle)
    }

    fun exportAccountDataBundleFile(accountId: Long): ExportedAccountDataFileBundle {
        val activity = activityProvider() ?: error("当前没有可用页面")
        val account = resolveAccountForDataExport(activity, accountId)
        val outputFile = File(activity.cacheDir, "reamicro_data_${account.accountId}_${System.currentTimeMillis()}.zip")
        runCatching {
            FileOutputStream(outputFile).buffered().use { output ->
                writeAccountDataZip(output, activity, account)
            }
        }.onFailure {
            outputFile.delete()
            throw it
        }
        return ExportedAccountDataFileBundle(
            account = AccountDisplayItem(
                accountId = account.accountId,
                nickname = account.nickname,
                identity = displayIdentity(account.email, account.thirdBinds),
                isCurrent = currentAccountPreview(activity)?.accountId == account.accountId,
            ),
            fileName = buildAccountDataExportFileName(account.accountId),
            file = outputFile,
        )
    }

    fun exportAllAccountDataBundleFiles(): List<ExportedAccountDataFileBundle> {
        val accountIds = displayAccounts().map { it.accountId }.distinct()
        if (accountIds.isEmpty()) error("暂无可导出的账号")
        persistCurrentAccountSnapshot()
        return accountIds.map(::exportAccountDataBundleFile)
    }

    fun exportAccountBooksBundle(accountId: Long): ExportedAccountBooksBundle {
        val activity = activityProvider() ?: error("当前没有可用页面")
        val account = resolveAccountForDataExport(activity, accountId)
        val root = File(activity.filesDir, "${account.accountId}/books")
        if (!root.exists() || !root.isDirectory) error("该账号暂无图书")
        val bookFiles = root.walkTopDown().filter { it.isFile }.toList()
        val bookDirs = root.listFiles()
            ?.filter { it.isDirectory && it.walkTopDown().any(File::isFile) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (bookDirs.isEmpty()) error("No exportable book directories")
        val bookRowsByUuid = loadAccountBookRows(activity, account.accountId)
            .associateBy { it.optString("uuid").trim() }
        val exportedBooks = JSONArray()
        val usedNames = linkedSetOf<String>()
        val exportPlans = bookDirs.map { bookDir ->
            val uuid = bookDir.name.trim()
            val row = bookRowsByUuid[uuid]
            val title = row?.optString("title")?.trim()
                ?.takeIf(String::isNotBlank)
                ?: row?.optString("name")?.trim()?.takeIf(String::isNotBlank)
                ?: uuid
            val entryName = uniqueBookArchiveEntryName(usedNames, title)
            exportedBooks.put(
                JSONObject().apply {
                    put("uuid", uuid)
                    put("title", title)
                    put("file", entryName)
                },
            )
            BookExportPlan(uuid = uuid, title = title, directory = bookDir, entryName = entryName)
        }
        if (bookFiles.isEmpty()) error("该账号暂无图书")
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                writeJsonEntry(
                    zip,
                    "manifest.json",
                    JSONObject().apply {
                        put("type", "account_books_export")
                        put("format", 2)
                        put("accountId", account.accountId)
                        put("nickname", account.nickname)
                        put("identity", displayIdentity(account.email, account.thirdBinds))
                        put("exportedAt", System.currentTimeMillis())
                        put("fileCount", bookFiles.size)
                        put("bookCount", exportedBooks.length())
                        put("books", exportedBooks)
                    },
                )
                exportPlans.forEach { plan ->
                    addBytesEntry(zip, buildEpubBytes(plan.directory), plan.entryName)
                }
            }
            output.toByteArray()
        }
        return ExportedAccountBooksBundle(
            account = AccountDisplayItem(
                accountId = account.accountId,
                nickname = account.nickname,
                identity = displayIdentity(account.email, account.thirdBinds),
                isCurrent = currentAccountPreview(activity)?.accountId == account.accountId,
            ),
            fileName = buildAccountBooksExportFileName(account.accountId),
            bytes = bytes,
        )
    }

    fun exportAccountBooksBundleFile(accountId: Long): ExportedAccountBooksFileBundle {
        val activity = activityProvider() ?: error("当前没有可用页面")
        val account = resolveAccountForDataExport(activity, accountId)
        val root = File(activity.filesDir, "${account.accountId}/books")
        if (!root.exists() || !root.isDirectory) error("该账号暂无图书")
        val bookFiles = root.walkTopDown().filter { it.isFile }.toList()
        val bookDirs = root.listFiles()
            ?.filter { it.isDirectory && it.walkTopDown().any(File::isFile) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (bookFiles.isEmpty() || bookDirs.isEmpty()) error("该账号暂无图书")
        val bookRowsByUuid = loadAccountBookRows(activity, account.accountId)
            .associateBy { it.optString("uuid").trim() }
        val exportedBooks = JSONArray()
        val usedNames = linkedSetOf<String>()
        val exportPlans = bookDirs.map { bookDir ->
            val uuid = bookDir.name.trim()
            val row = bookRowsByUuid[uuid]
            val title = row?.optString("title")?.trim()
                ?.takeIf(String::isNotBlank)
                ?: row?.optString("name")?.trim()?.takeIf(String::isNotBlank)
                ?: uuid
            val entryName = uniqueBookArchiveEntryName(usedNames, title)
            exportedBooks.put(
                JSONObject().apply {
                    put("uuid", uuid)
                    put("title", title)
                    put("file", entryName)
                },
            )
            BookExportPlan(uuid = uuid, title = title, directory = bookDir, entryName = entryName)
        }
        val outputFile = File(activity.cacheDir, "reamicro_books_${account.accountId}_${System.currentTimeMillis()}.zip")
        FileOutputStream(outputFile).buffered().use { output ->
            writeAccountBooksZip(
                output = output,
                account = account,
                bookFilesCount = bookFiles.size,
                exportedBooks = exportedBooks,
                exportPlans = exportPlans,
                tempDir = activity.cacheDir,
            )
        }
        return ExportedAccountBooksFileBundle(
            account = AccountDisplayItem(
                accountId = account.accountId,
                nickname = account.nickname,
                identity = displayIdentity(account.email, account.thirdBinds),
                isCurrent = currentAccountPreview(activity)?.accountId == account.accountId,
            ),
            fileName = buildAccountBooksExportFileName(account.accountId),
            file = outputFile,
        )
    }

    private fun captureCurrentAccount(activity: Activity): StoredAccount? {
        val identity = AccountHostRuntime.snapshot(activity) ?: return null
        val current = currentAccountPreview(activity, identity) ?: return null
        val restoring = advancedSettings.preserved(current.accountId)
        val snapshot = captureSessionSnapshot(identity.session)
        if (snapshot.token != identity.token || !AccountHostRuntime.isCurrent(identity, activity)) return null
        val previous = loadStoredAccounts(activity).firstOrNull { it.accountId == current.accountId }?.advancedPreferences
        val account = current.toStoredAccount(
            session = snapshot,
            webDavPrefs = captureSharedPrefsSnapshot(activity, WEBDAV_PREFS),
            localLibraryPrefs = captureSharedPrefsSnapshot(activity, LOCAL_LIBRARY_PREFS),
            hasFullState = true,
        ).copy(advancedPreferences = restoring ?: advancedSettings.capture(current.accountId, snapshot.advanced(), previous))
        return account.takeIf { AccountHostRuntime.isCurrent(identity, activity) }
    }

    private fun currentAccountPreview(
        activity: Activity, identity: AccountHostRuntime.Identity? = AccountHostRuntime.snapshot(activity),
    ): CurrentAccountPreview? {
        identity ?: return null
        val currentUser = identity.user ?: return null
        val accountId = identity.accountId
        val userData = currentUser.stringValue("getData")
        val info = host.method("info").invoke(currentUser)
        val nickname = info?.let { it.stringValue("getNickName") }.orEmpty()
        val email = info?.let { it.stringValue("getEmail") }.orEmpty()
        val thirdBinds = (info?.let { host.field("info.thirdBinds").get(it) } as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (!AccountHostRuntime.isCurrent(identity, activity)) return null
        return CurrentAccountPreview(
            accountId = accountId,
            token = identity.token,
            userData = userData,
            nickname = nickname,
            email = email,
            thirdBinds = thirdBinds,
        )
    }

    private fun restoreAccount(activity: Activity, account: StoredAccount, restoreDevicePreferences: Boolean = false) =
        synchronized(advancedSettings.operationLock) {
            advancedSettings.requireIdentityMutation()
            require(account.accountId > 0L && account.token.isNotBlank() && account.userData.isNotBlank()) {
                "账号凭证内容不完整"
            }
            val repository = AccountHostRuntime.repository(activity) ?: error("无法获取阅微账号仓库")
            val session = requireNotNull(host.field("repository.session").get(repository)) { "无法获取阅微会话" }
            val dao = requireNotNull(host.field("repository.dao").get(repository)) { "无法获取阅微账号数据" }
            val store = requireNotNull(preferences.field("store").get(session)) { "无法获取阅微设置" }
            val before = captureSessionSnapshot(session)
            val previousUser = host.field("cachedUser").get(null)
            val previousToken = (preferences.field("token").get(session) as? String).orEmpty()
            check(before.token == previousToken &&
                (previousUser == null || host.field("user.token").get(previousUser) == previousToken)) {
                "阅微账号正在更新，请稍后重试"
            }
            val previousWebDav = captureSharedPrefsSnapshot(activity, WEBDAV_PREFS)
            val previousLibrary = captureSharedPrefsSnapshot(activity, LOCAL_LIBRARY_PREFS)
            val guarded = guardUserDataAgainstHost(activity, account)
            val targetUser = createHostUser(guarded)
            val targetInfo = requireNotNull(host.method("info").invoke(targetUser)) { "账号资料内容无效" }
            check(host.field("info.id").getLong(targetInfo) == account.accountId) { "账号资料与凭证不一致" }
            val targetSync = AccountSyncPreferences.restoreValues(account.session.syncPreferences,
                requireNotNull(before.syncPreferences))
            val ticket = advancedSettings.begin(
                AccountAdvancedRestoreState.Target(account.accountId, account.token), account.advancedPreferences,
            )
            AccountHostRuntime.beginIdentityMutation()
            var mutationOpen = true
            fun finishIdentityMutation() {
                if (mutationOpen) {
                    AccountHostRuntime.endIdentityMutation()
                    mutationOpen = false
                }
            }
            var preferencesAttempted = false
            var accountSettingsAttempted = false
            var sessionTokenWritten = false
            var identityPublished = false
            val tokenApplied = AtomicBoolean(false)
            val rollbackTokenApplied = AtomicBoolean(false)
            var stage = "identity-preferences"
            fun nativeIdentityOwned(): Boolean {
                val token = preferences.field("token").get(session)
                val tokenOwned = if (sessionTokenWritten) {
                    token == account.token || rollbackTokenApplied.get() && token == previousToken
                } else {
                    token == previousToken || tokenApplied.get() && token == account.token
                }
                return tokenOwned && host.field("cachedUser").get(null) === previousUser &&
                    AccountHostRuntime.session(activity) === session && preferences.field("store").get(session) === store
            }
            try {
                preferencesAttempted = true
                advancedSettings.updatePreferences(
                    store, before.token, identityPreferences(account.token, account.session, targetSync), ::nativeIdentityOwned,
                    onApplied = { tokenApplied.set(true) },
                )
                check(nativeIdentityOwned()) { "阅微账号已经变化，请重试切换" }
                stage = "local-settings"
                accountSettingsAttempted = true
                restoreSharedPrefs(activity, WEBDAV_PREFS, account.webDavPrefs)
                restoreSharedPrefs(activity, LOCAL_LIBRARY_PREFS,
                    if (account.hasFullState) account.localLibraryPrefs else SharedPrefsSnapshot.EMPTY)
                if (account.hasFullState && restoreDevicePreferences)
                    restoreFullSessionPreferences(session, account.session, account.token, ::nativeIdentityOwned)
                check(nativeIdentityOwned()) { "阅微账号已经变化，请重试切换" }
                stage = "session-token"
                preferences.field("token").set(session, account.token)
                sessionTokenWritten = true
                stage = "user-upsert"
                invokeSuspendMethod(host.method("upsert"), dao, targetUser)
                check(nativeIdentityOwned()) { "阅微账号已经变化，请重试切换" }
                stage = "cache-publication"
                publishCachedUser(targetUser)
                identityPublished = true
                finishIdentityMutation()
                stage = "advanced-settings"
                advancedSettings.ready(ticket)
            } catch (error: Throwable) {
                runCatching { HookRuntime.logAlways("$LOG_PREFIX account switch failed stage=$stage type=${error.javaClass.simpleName}") }
                if (preferencesAttempted && !identityPublished) {
                    runCatching {
                        val restored = try {
                            advancedSettings.reconcilePreferences(
                                store, before.token, account.token, identityPreferences(before.token, before), ::nativeIdentityOwned,
                                onApplied = { rollbackTokenApplied.set(true) },
                            )
                        } catch (rollbackError: Throwable) {
                            error.addSuppressed(rollbackError)
                            // 回滚也可能提交后才返回失败；读取实际值，确认恢复后再修复会话和本地设置。
                            val actual = captureSessionSnapshot(session)
                            samePreferenceValues(identityPreferences(actual.token, actual), identityPreferences(before.token, before))
                        }
                        if (restored && nativeIdentityOwned()) {
                            // A native Flow may have written the target even before our direct session write.
                            preferences.field("token").set(session, previousToken)
                            sessionTokenWritten = false
                            if (accountSettingsAttempted) {
                                if (account.hasFullState && restoreDevicePreferences)
                                    restoreFullSessionPreferences(session, before, before.token, ::nativeIdentityOwned)
                                restoreSharedPrefs(activity, WEBDAV_PREFS, previousWebDav)
                                restoreSharedPrefs(activity, LOCAL_LIBRARY_PREFS, previousLibrary)
                            }
                            tokenApplied.set(false)
                            rollbackTokenApplied.set(false)
                        }
                    }.onFailure(error::addSuppressed)
                }
                finishIdentityMutation()
                advancedSettings.cancel(ticket)
                throw error
            } finally {
                finishIdentityMutation()
            }
        }

    private fun identityPreferences(
        token: String, session: SessionSnapshot, syncValues: Map<String, Any?> = session.syncPreferences?.values().orEmpty(),
    ): Map<Any, Any?> = linkedMapOf<Any, Any?>(
        key(PREF_KEYS_CLASS, "getTOKEN") to token,
        key(THIRD_PARTY_KEYS_CLASS, "getBAIDU_AUTH") to session.baiduAuth,
        key(THIRD_PARTY_KEYS_CLASS, "getALIYUN_AUTH") to session.aliyunAuth,
        key(THIRD_PARTY_KEYS_CLASS, "getYUN115_AUTH") to session.yun115Auth,
    ).apply { syncValues.forEach { (name, value) -> put(preferences.key(name), value) } }

    private fun samePreferenceValues(left: Map<Any, Any?>, right: Map<Any, Any?>): Boolean =
        left.keys == right.keys && left.all { (key, value) ->
            val other = right[key]
            if (value is ByteArray && other is ByteArray) value.contentEquals(other) else value == other
        }

    private fun restoreFullSessionPreferences(
        session: Any, snapshot: SessionSnapshot, expectedToken: String, canWrite: () -> Boolean,
    ) {
        // Device layout is restored in one account-bound edit; native membership still controls the five advanced values.
        val values = linkedMapOf<Any, Any?>()
        fun put(getter: String, value: Any) { values[key(PREF_KEYS_CLASS, getter)] = value }
        put("getDARK_MODE", snapshot.darkMode)
        put("getBACKUP_TYPE", snapshot.backupType)
        put("getBOOKSHELF_TYPE", snapshot.bookshelfType)
        put("getTHEME", snapshot.theme)
        put("getMIPMAP", snapshot.mipmap)
        put("getBACKGROUND", snapshot.background)
        put("getTEXT_SIZE", ReaderTypographyPolicy.textSize(snapshot.textSize))
        put("getLINE_HEIGHT", ReaderTypographyPolicy.lineHeight(snapshot.lineHeight))
        put("getFAMILY", snapshot.family)
        put("getEMBEDDED_FONTS", snapshot.embeddedFonts)
        put("getBUILD_IN_FONTS", snapshot.builtInFonts)
        put("getPADDING", snapshot.padding)
        val margins = snapshot.readerMargins ?: ReaderMarginsSnapshot.fromJson(null, snapshot.padding)
        listOf("padding_top", "padding_bottom", "padding_left", "padding_right", "padding_top_outer", "padding_bottom_outer")
            .zip(margins.arguments().toList()).forEach { (name, value) -> values[preferences.key(name)] = value }
        put("getONLY_NEXT_PAGE", snapshot.onlyNextPage)
        put("getVOLUME_KEY", snapshot.volumeKey)
        put("getTIME_BATTERY", snapshot.timeBattery)
        put("getSCREEN_ALWAYS_ON", snapshot.screenAlwaysOn)
        put("getSYSTEM_BARS", snapshot.systemBars)
        put("getAGREEMENT", snapshot.agreement)
        advancedSettings.updatePreferences(requireNotNull(preferences.field("store").get(session)), expectedToken, values, canWrite)
    }

    private fun captureSessionSnapshot(session: Any): SessionSnapshot {
        val dataStore = preferences.field("store").get(session)
        val dataFlow = requireNotNull(preferences.method("data").invoke(dataStore))
        val prefs = firstFlowValue(dataFlow) ?: error("读取阅微设置失败")
        return SessionSnapshot(
            token = readPreference(prefs, key(PREF_KEYS_CLASS, "getTOKEN")) as? String ?: session.stringValue("getToken"),
            dynamicColor = readPreference(prefs, key(PREF_KEYS_CLASS, "getDYNAMIC_COLOR")) as? Boolean ?: false,
            darkMode = (readPreference(prefs, key(PREF_KEYS_CLASS, "getDARK_MODE")) as? Number)?.toInt() ?: 0,
            backupType = (readPreference(prefs, key(PREF_KEYS_CLASS, "getBACKUP_TYPE")) as? Number)?.toInt() ?: 0,
            bookshelfType = (readPreference(prefs, key(PREF_KEYS_CLASS, "getBOOKSHELF_TYPE")) as? Number)?.toInt() ?: 0,
            theme = readPreference(prefs, key(PREF_KEYS_CLASS, "getTHEME")) as? String ?: "",
            mipmap = readPreference(prefs, key(PREF_KEYS_CLASS, "getMIPMAP")) as? String ?: "",
            background = readPreference(prefs, key(PREF_KEYS_CLASS, "getBACKGROUND")) as? String ?: "",
            textSize = ReaderTypographyPolicy.textSize((readPreference(prefs, key(PREF_KEYS_CLASS, "getTEXT_SIZE")) as? Number)?.toFloat()),
            lineHeight = ReaderTypographyPolicy.lineHeight((readPreference(prefs, key(PREF_KEYS_CLASS, "getLINE_HEIGHT")) as? Number)?.toFloat()),
            family = readPreference(prefs, key(PREF_KEYS_CLASS, "getFAMILY")) as? String ?: "",
            embeddedFonts = readPreference(prefs, key(PREF_KEYS_CLASS, "getEMBEDDED_FONTS")) as? Boolean ?: true,
            builtInFonts = readPreference(prefs, key(PREF_KEYS_CLASS, "getBUILD_IN_FONTS")) as? Boolean ?: false,
            padding = (readPreference(prefs, key(PREF_KEYS_CLASS, "getPADDING")) as? Number)?.toInt() ?: 0,
            readerMargins = captureReaderMargins(prefs),
            onlyNextPage = readPreference(prefs, key(PREF_KEYS_CLASS, "getONLY_NEXT_PAGE")) as? Boolean ?: false,
            volumeKey = readPreference(prefs, key(PREF_KEYS_CLASS, "getVOLUME_KEY")) as? Boolean ?: false,
            timeBattery = readPreference(prefs, key(PREF_KEYS_CLASS, "getTIME_BATTERY")) as? Boolean ?: false,
            screenAlwaysOn = readPreference(prefs, key(PREF_KEYS_CLASS, "getSCREEN_ALWAYS_ON")) as? Boolean ?: false,
            systemBars = readPreference(prefs, key(PREF_KEYS_CLASS, "getSYSTEM_BARS")) as? Boolean ?: false,
            agreement = readPreference(prefs, key(PREF_KEYS_CLASS, "getAGREEMENT")) as? Boolean ?: false,
            smartRecognition = readPreference(prefs, key(PREF_KEYS_CLASS, "getSMART_RECOGNITION")) as? Boolean ?: false,
            illustrationOptimization = readPreference(prefs, key(PREF_KEYS_CLASS, "getILLUSTRATION_OPTIMIZATION")) as? Boolean ?: false,
            relateDownloadCover = readPreference(prefs, key(PREF_KEYS_CLASS, "getRELATE_DOWNLOAD_COVER")) as? Boolean ?: false,
            callingCardColor = readPreference(prefs, key(PREF_KEYS_CLASS, "getCALLING_CARD_COLOR")) as? Boolean ?: false,
            baiduAuth = readPreference(prefs, key(THIRD_PARTY_KEYS_CLASS, "getBAIDU_AUTH")) as? String ?: "",
            aliyunAuth = readPreference(prefs, key(THIRD_PARTY_KEYS_CLASS, "getALIYUN_AUTH")) as? String ?: "",
            yun115Auth = readPreference(prefs, key(THIRD_PARTY_KEYS_CLASS, "getYUN115_AUTH")) as? String ?: "",
            syncPreferences = AccountSyncPreferences.capture { readPreference(prefs, preferences.key(it)) },
        )
    }

    private fun captureReaderMargins(prefs: Any): ReaderMarginsSnapshot {
        val padding = (readPreference(prefs, key(PREF_KEYS_CLASS, "getPADDING")) as? Number)?.toInt() ?: 0
        fun edge(name: String, fallback: Int) = (readPreference(prefs, preferences.key(name)) as? Number)?.toInt() ?: fallback
        return ReaderMarginsSnapshot(edge("padding_top", padding), edge("padding_bottom", padding),
            edge("padding_left", padding), edge("padding_right", padding),
            edge("padding_top_outer", 0), edge("padding_bottom_outer", 0))
    }
    private fun captureSharedPrefsSnapshot(activity: Activity, prefsName: String): SharedPrefsSnapshot =
        SharedPrefsSnapshot.fromPreferences(
            activity.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        )

    private fun restoreSharedPrefs(activity: Activity, prefsName: String, snapshot: SharedPrefsSnapshot) {
        val prefs = activity.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val editor = prefs.edit().clear()
        snapshot.applyTo(editor)
        check(editor.commit()) { "保存账号设置失败" }
    }

    private fun currentSession(activity: Activity): Any? = AccountHostRuntime.session(activity)
    private fun currentAccountTarget(): AccountAdvancedRestoreState.Target? =
        AccountHostRuntime.snapshot(activityProvider())?.let { AccountAdvancedRestoreState.Target(it.accountId, it.token) }
    @Suppress("UNUSED_PARAMETER")
    private fun currentCachedUser(activity: Activity): Any? = AccountHostRuntime.user()

    private fun resolveAccountForDataExport(activity: Activity, accountId: Long): StoredAccount {
        return synchronized(advancedSettings.operationLock) {
            val stored = loadStoredAccounts(activity)
                .associateByTo(linkedMapOf()) { it.accountId }
            val currentId = currentAccountPreview(activity)?.accountId
            if (currentId == accountId) {
                val currentSnapshot = captureCurrentAccount(activity)
                if (currentSnapshot != null) {
                    val normalized = currentSnapshot.copy(lastUsedAt = System.currentTimeMillis())
                    val existing = stored[normalized.accountId]
                    val merged = existing?.mergeWith(normalized) ?: normalized
                    stored[normalized.accountId] = merged
                    saveStoredAccounts(activity, stored.values.toList())
                    return@synchronized merged
                }
            }
            return@synchronized stored[accountId] ?: error("未找到账号凭证")
        }
    }

    private fun buildAccountExportManifest(activity: Activity, account: StoredAccount): JSONObject =
        JSONObject().apply {
            put("type", "account_data_export")
            put("format", 1)
            put("exportedAt", System.currentTimeMillis())
            put("accountId", account.accountId)
            put("nickname", account.nickname)
            put("identity", displayIdentity(account.email, account.thirdBinds))
            put("isCurrent", currentAccountPreview(activity)?.accountId == account.accountId)
            put("hasFullState", account.hasFullState)
            put("includes", JSONArray().apply {
                put("account/stored_account.json")
                put("account/session.json")
                put("account/webdav_prefs.json")
                put("account/local_library_prefs.json")
                put(LOCAL_TASK_BUNDLE_ENTRY)
                put("database/users.json")
                put("database/books.json")
                put("database/book_item_ref.json")
                put("database/book_chapter.json")
                put("database/book_reading.json")
                put("files/${account.accountId}/")
                put("module/shared_prefs.json")
                put("module/files/")
            })
        }

    private fun readBundleManifest(zip: ZipFile): JSONObject {
        val bytes = readBundleEntryBytes(zip, "manifest.json") ?: error("\u7f3a\u5c11\u6570\u636e\u5305\u6e05\u5355")
        val manifest = JSONObject(String(bytes, Charsets.UTF_8))
        if (manifest.optString("type") != "account_data_export" || manifest.optInt("format", -1) != 1) {
            error("\u4e0d\u652f\u6301\u7684\u6570\u636e\u5305\u683c\u5f0f")
        }
        return manifest
    }

    private fun readBundleAccount(zip: ZipFile): StoredAccount {
        val bytes = readBundleEntryBytes(zip, "account/stored_account.json")
            ?: error("\u7f3a\u5c11\u8d26\u53f7\u6570\u636e")
        val account = StoredAccount.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
        validateStoredAccount(account)
        return account
    }

    private fun readBundleEntryBytes(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        require(!entry.isDirectory && entry.size <= MAX_BUNDLE_METADATA_BYTES) { "数据包账号信息过大或无效" }
        return zip.getInputStream(entry).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size().toLong() + count <= MAX_BUNDLE_METADATA_BYTES) { "数据包账号信息过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun validateBundleAccount(manifest: JSONObject, account: StoredAccount) {
        require(manifest.optLong("accountId", 0L) == account.accountId) { "数据包清单与账号不一致" }
    }

    private fun validateStoredAccount(account: StoredAccount) {
        require(account.accountId > 0L && account.token.isNotBlank() && account.userData.isNotBlank()) { "账号凭证内容不完整" }
        require(account.session.token.isBlank() || account.session.token == account.token) { "账号会话与凭证不一致" }
        val info = requireNotNull(host.method("info").invoke(createHostUser(account))) { "账号资料内容无效" }
        require(host.field("info.id").getLong(info) == account.accountId) { "账号资料与凭证不一致" }
    }

    // 只做清单校验与文件统计，不把整包解压到内存，供导入确认弹窗使用。
    fun previewAccountDataBundleFile(file: File, displayName: String = ""): AccountDataBundlePreview {
        ZipFile(file).use { zip ->
            val manifest = readBundleManifest(zip)
            val account = readBundleAccount(zip)
            validateBundleAccount(manifest, account)
            val localTasks = readBundleEntryBytes(zip, LOCAL_TASK_BUNDLE_ENTRY)
            localTasks?.let { LocalTaskAccountBundle.read(JSONObject(String(it, Charsets.UTF_8)), account.accountId.toString()) }
            val filePrefix = "files/${account.accountId}/"
            var fileCount = 0
            var uncompressedBytes = 0L
            var compressedBytes = 0L
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(filePrefix)) continue
                fileCount++
                if (entry.size > 0L) uncompressedBytes += entry.size
                if (entry.compressedSize > 0L) compressedBytes += entry.compressedSize
            }
            return AccountDataBundlePreview(
                accountId = account.accountId,
                nickname = account.nickname,
                identity = displayIdentity(account.email, account.thirdBinds),
                fileName = displayName.trim().ifBlank { file.name },
                exportedAt = manifest.optLong("exportedAt", 0L),
                fileCount = fileCount,
                uncompressedBytes = uncompressedBytes,
                compressedBytes = compressedBytes,
                archiveBytes = file.length(),
                hasLocalTasks = localTasks != null,
            )
        }
    }

    // 流式导入：数据库逐行写入，图书与模块文件逐条复制，避免整包常驻内存。
    fun importAccountDataBundle(file: File, onProgress: (String) -> Unit = {}): ImportedAccountDataBundle =
        synchronized(advancedSettings.operationLock) {
            advancedSettings.requireIdentityMutation()
            val activity = activityProvider() ?: error("\u5f53\u524d\u6ca1\u6709\u53ef\u7528\u9875\u9762")
            ZipFile(file).use { zip ->
                val manifest = readBundleManifest(zip)
                val imported = readBundleAccount(zip)
                validateBundleAccount(manifest, imported)
                val account = guardUserDataAgainstHost(activity, imported)
                validateStoredAccount(account)
                val taskStore = LocalTaskStore { activity.applicationContext }
                val taskRestore = readBundleEntryBytes(zip, LOCAL_TASK_BUNDLE_ENTRY)?.let { bytes ->
                    taskStore.prepareAccountBundle(account.accountId.toString(), account.token,
                        JSONObject(String(bytes, Charsets.UTF_8)))
                }
                persistCurrentAccountSnapshot()
                onProgress("正在暂存图书文件")
                stageUserFilesStreaming(zip, account.accountId, onProgress).use { files ->
                    onProgress("正在恢复书库数据")
                    restoreHostDatabaseEntriesStreaming(zip, account) { files.install() }
                    files.complete()
                }
                onProgress("\u6b63\u5728\u6062\u590d\u6a21\u5757\u8bbe\u7f6e")
                restoreModuleGlobalEntriesStreaming(zip)
                val normalized = account.copy(lastUsedAt = System.currentTimeMillis())
                restoreAccount(activity, normalized, restoreDevicePreferences = true)
                val stored = loadStoredAccounts(activity)
                    .associateByTo(linkedMapOf()) { it.accountId }
                val existing = stored[normalized.accountId]
                stored[normalized.accountId] = existing?.mergeWith(normalized) ?: normalized
                saveStoredAccounts(activity, stored.values.toList())
                if (taskRestore != null) {
                    val identity = AccountHostRuntime.snapshot(activity)
                    check(identity != null && identity.accountId == normalized.accountId && identity.token == normalized.token) {
                        "阅微账号已经变化，请重新导入"
                    }
                    onProgress("正在恢复本地任务配置与记录")
                    // This is the final durable restore step: earlier import failures retain the old tasks.
                    taskStore.restoreAccountBundle(taskRestore) { AccountHostRuntime.isCurrent(identity, activity) }
                    // The backup's enabled flags are retained; delivery acknowledgement is handled by the task bridge.
                    runCatching { LocalTaskMirror.push(activity.applicationContext) }.onFailure {
                        HookRuntime.logError("ReaMicro account local task mirror dispatch failed")
                    }
                }
                ImportedAccountDataBundle(
                    account = AccountDisplayItem(
                        accountId = normalized.accountId,
                        nickname = normalized.nickname,
                        identity = displayIdentity(normalized.email, normalized.thirdBinds),
                        isCurrent = true,
                    ),
                )
            }
        }

    private fun restoreHostDatabaseEntriesStreaming(zip: ZipFile, account: StoredAccount, installUserFiles: () -> Unit) {
        val accountId = account.accountId
        // A missing table payload is an incomplete backup, not an instruction to erase the account.
        listOf("books", "book_item_ref", "book_chapter", "book_reading").forEach { table ->
            require(zip.getEntry("database/$table.json")?.isDirectory == false) { "数据包书库数据不完整" }
        }
        val activity = activityProvider() ?: error("\u5f53\u524d\u6ca1\u6709\u53ef\u7528\u9875\u9762")
        val databasePath = activity.applicationContext.getDatabasePath(HOST_DB_NAME)
        if (!databasePath.exists()) error("\u672a\u627e\u5230\u9605\u5fae\u6570\u636e\u5e93")
        val database = openHostDatabaseReadWrite(databasePath.absolutePath)
        try {
            database.beginTransaction()
            val oldBookIds = queryRows(
                database = database,
                sql = "SELECT id FROM books WHERE uid = ?",
                selectionArgs = arrayOf(accountId.toString()),
            ).mapNotNull { (it.opt("id") as? Number)?.toLong() }
            database.delete("book_reading", "uid = ?", arrayOf(accountId.toString()))
            deleteRowsForIds(database, "book_item_ref", "book_id", oldBookIds)
            deleteRowsForIds(database, "book_chapter", "book_id", oldBookIds)
            database.delete("books", "uid = ?", arrayOf(accountId.toString()))
            // The native User record has exactly these four fields in both hosts. Bundle rows must never overwrite other users
            // or replace current native account information before guardUserDataAgainstHost can preserve it.
            insertOrReplace(database, "users", ContentValues().apply {
                put("id", accountId); put("token", account.token); put("data", account.userData)
                put("updated", System.currentTimeMillis())
            })
            val bookIdMap = linkedMapOf<Long, Long>()
            forEachBundleRow(zip, "database/books.json") { row ->
                val oldId = (row.opt("id") as? Number)?.toLong() ?: 0L
                require(oldId > 0L && !bookIdMap.containsKey(oldId)) { "数据包图书编号无效或重复" }
                val newId = requireNotNull(insertImportedBooks(database, accountId, row)) { "恢复图书数据失败" }
                bookIdMap[oldId] = newId
            }
            forEachBundleRow(zip, "database/book_item_ref.json") { row ->
                insertImportedChildRow(database, "book_item_ref", row, bookIdMap)
            }
            forEachBundleRow(zip, "database/book_chapter.json") { row ->
                insertImportedChildRow(database, "book_chapter", row, bookIdMap)
            }
            forEachBundleRow(zip, "database/book_reading.json") { row ->
                insertImportedReadingRow(database, accountId, row, bookIdMap)
            }
            installUserFiles()
            database.setTransactionSuccessful()
        } finally {
            try { database.endTransaction() } finally { database.close() }
        }
    }

    // 逐行读取数据包内的 JSON 数组，避免一次性构造整棵 JSON 树。
    private fun forEachBundleRow(zip: ZipFile, entryName: String, action: (JSONObject) -> Unit) {
        val entry = zip.getEntry(entryName) ?: return
        zip.getInputStream(entry).buffered().use { input ->
            val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            reader.beginArray()
            while (reader.hasNext()) {
                action(readJsonRecord(reader))
            }
            reader.endArray()
        }
    }

    private fun readJsonRecord(reader: JsonReader): JSONObject {
        val record = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (val value = readJsonValue(reader)) {
                null -> record.put(name, JSONObject.NULL)
                else -> record.put(name, value)
            }
        }
        reader.endObject()
        return record
    }

    private fun readJsonValue(reader: JsonReader): Any? =
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readJsonRecord(reader)
            JsonToken.BEGIN_ARRAY -> readJsonArrayValue(reader)
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> {
                val text = reader.nextString()
                text.toLongOrNull() ?: text.toDoubleOrNull() ?: text
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> {
                reader.nextNull()
                JSONObject.NULL
            }
            else -> null
        }

    private fun readJsonArrayValue(reader: JsonReader): JSONArray {
        val array = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) {
            array.put(readJsonValue(reader))
        }
        reader.endArray()
        return array
    }

    private fun stageUserFilesStreaming(zip: ZipFile, accountId: Long, onProgress: (String) -> Unit): AccountUserFilesRestore {
        val activity = activityProvider() ?: error("当前没有可用页面")
        val restore = AccountUserFilesRestore(File(activity.filesDir, accountId.toString()))
        try {
            val prefix = "files/$accountId/"
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.toList()
            val rootPath = restore.staged.canonicalFile
            var written = 0
            entries.forEach { entry ->
                val relativePath = entry.name.removePrefix(prefix)
                require(isSafeArchivePath(relativePath)) { "数据包图书路径无效" }
                val target = File(rootPath, relativePath).canonicalFile
                require(target.path.startsWith(rootPath.path + File.separator) && !target.exists()) { "数据包图书路径无效或重复" }
                val parent = requireNotNull(target.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "无法暂存图书目录" }
                copyBundleFile(zip, entry, target)
                written++
                if (written % 500 == 0) onProgress("正在暂存图书文件 $written/${entries.size}")
            }
            return restore
        } catch (error: Throwable) {
            runCatching { restore.close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun copyBundleFile(zip: ZipFile, entry: ZipEntry, target: File) {
        val checksum = CRC32()
        var written = 0L
        zip.getInputStream(entry).use { input ->
            FileOutputStream(target).buffered().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    checksum.update(buffer, 0, count)
                    written += count
                }
            }
        }
        check((entry.size < 0L || written == entry.size) && (entry.crc < 0L || checksum.value == entry.crc)) { "数据包图书文件损坏" }
    }

    private fun restoreModuleGlobalEntriesStreaming(zip: ZipFile) {
        val activity = activityProvider() ?: error("\u5f53\u524d\u6ca1\u6709\u53ef\u7528\u9875\u9762")
        readBundleEntryBytes(zip, "module/shared_prefs.json")?.let { bytes ->
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val iterator = json.keys()
            while (iterator.hasNext()) {
                val prefsName = iterator.next()
                if (prefsName !in MODULE_BACKUP_PREFS) continue
                val snapshotJson = json.optJSONObject(prefsName) ?: continue
                restoreSharedPrefs(activity, prefsName, SharedPrefsSnapshot.fromJson(snapshotJson))
            }
        }
        val prefix = "module/files/"
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(prefix) }
            .mapNotNull { entry ->
                val relativePath = entry.name.removePrefix(prefix)
                if (!isSafeArchivePath(relativePath) || !isAllowedModuleBackupFile(relativePath)) {
                    null
                } else {
                    relativePath to entry
                }
            }
            .toList()
        if (entries.isEmpty()) return
        val root = activity.filesDir.canonicalFile
        MODULE_BACKUP_FILES.forEach { fileName ->
            runCatching { File(root, fileName).delete() }
        }
        MODULE_BACKUP_DIRS.forEach { dirName ->
            runCatching { File(root, dirName).deleteRecursively() }
        }
        entries.forEach { (relativePath, entry) ->
            val target = File(root, relativePath).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) return@forEach
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                FileOutputStream(target).buffered().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun isSafeArchivePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || '\\' in path) return false
        return path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    // 宿主用 androidx.sqlite 捆绑驱动以 WAL 打开 app.db。
    // 框架 SQLiteDatabase 默认会把日志模式改成 delete 并截断 -wal，
    // 宿主仍打开的连接会立刻 SQLITE_CORRUPT 崩溃，因此这里强制保持 WAL。
    private fun openHostDatabaseReadWrite(path: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            path,
            null,
            SQLiteDatabase.OPEN_READWRITE
                or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
                or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )

    private fun insertImportedBooks(
        database: SQLiteDatabase,
        accountId: Long,
        row: JSONObject,
    ): Long? {
        val oldId = (row.opt("id") as? Number)?.toLong() ?: 0L
        val restoredRow = copyJsonRow(
            row = row,
            overrides = mapOf("uid" to accountId),
            nullColumns = setOf("id"),
        )
        val newId = insertOrReplace(database, "books", jsonToContentValues(restoredRow))
        return newId.takeIf { it > 0L && oldId > 0L }
    }

    private fun insertImportedChildRow(
        database: SQLiteDatabase,
        table: String,
        row: JSONObject,
        bookIdMap: Map<Long, Long>,
    ) {
        val oldBookId = (row.opt("book_id") as? Number)?.toLong() ?: return
        val newBookId = bookIdMap[oldBookId] ?: return
        val restoredRow = copyJsonRow(
            row = row,
            overrides = mapOf("book_id" to newBookId),
            nullColumns = setOf("id"),
        )
        insertOrReplace(database, table, jsonToContentValues(restoredRow))
    }

    private fun insertImportedReadingRow(
        database: SQLiteDatabase,
        accountId: Long,
        row: JSONObject,
        bookIdMap: Map<Long, Long>,
    ) {
        val oldBookId = (row.opt("book_id") as? Number)?.toLong() ?: return
        val newBookId = bookIdMap[oldBookId] ?: return
        val restoredRow = copyJsonRow(
            row = row,
            overrides = mapOf(
                "uid" to accountId,
                "book_id" to newBookId,
            ),
            nullColumns = setOf("id"),
        )
        insertOrReplace(database, "book_reading", jsonToContentValues(restoredRow))
    }

    private fun deleteRowsForIds(
        database: SQLiteDatabase,
        table: String,
        idColumn: String,
        ids: List<Long>,
    ) {
        ids.chunked(DATABASE_EXPORT_ID_CHUNK_SIZE).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val placeholders = chunk.joinToString(",") { "?" }
            database.delete(
                table,
                "$idColumn IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray(),
            )
        }
    }

    private fun copyJsonRow(
        row: JSONObject,
        overrides: Map<String, Any?>,
        nullColumns: Set<String>,
    ): JSONObject =
        JSONObject().apply {
            val iterator = row.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                when {
                    key in nullColumns -> put(key, JSONObject.NULL)
                    overrides.containsKey(key) -> put(key, overrides[key] ?: JSONObject.NULL)
                    else -> put(key, row.opt(key))
                }
            }
            overrides.forEach { (key, value) ->
                if (!has(key)) put(key, value ?: JSONObject.NULL)
            }
            nullColumns.forEach { key ->
                if (!has(key)) put(key, JSONObject.NULL)
            }
        }

    private fun insertOrReplace(database: SQLiteDatabase, table: String, values: ContentValues): Long {
        if (values.size() == 0) return -1L
        val columns = values.keySet().toList()
        val sql = buildString {
            append("INSERT OR REPLACE INTO ")
            append(quoteSqlIdentifier(table))
            append(" (")
            append(columns.joinToString(",") { quoteSqlIdentifier(it) })
            append(") VALUES (")
            append(columns.joinToString(",") { "?" })
            append(")")
        }
        val statement = database.compileStatement(sql)
        return try {
            columns.forEachIndexed { index, column ->
                bindStatementValue(statement, index + 1, values.get(column))
            }
            statement.executeInsert()
        } finally {
            statement.close()
        }
    }

    private fun bindStatementValue(statement: SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.bindNull(index)
            is ByteArray -> statement.bindBlob(index, value)
            is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
            is Int -> statement.bindLong(index, value.toLong())
            is Long -> statement.bindLong(index, value)
            is Float -> statement.bindDouble(index, value.toDouble())
            is Double -> statement.bindDouble(index, value)
            is Number -> statement.bindLong(index, value.toLong())
            else -> statement.bindString(index, value.toString())
        }
    }

    private fun quoteSqlIdentifier(identifier: String): String =
        "`" + identifier.replace("`", "``") + "`"

    private fun jsonToContentValues(json: JSONObject): ContentValues =
        ContentValues().apply {
            val iterator = json.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val value = json.opt(key)
                when (value) {
                    null, JSONObject.NULL -> putNull(key)
                    is Boolean -> put(key, if (value) 1 else 0)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Float -> put(key, value)
                    is Double -> put(key, value)
                    is Number -> put(key, value.toLong())
                    is String -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }

    private fun writeHostDatabaseEntries(zip: ZipOutputStream, activity: Activity, account: StoredAccount) {
        val databasePath = activity.applicationContext.getDatabasePath(HOST_DB_NAME)
        if (!databasePath.exists()) return
        val database = SQLiteDatabase.openDatabase(
            databasePath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val userRows = queryRows(
                database = database,
                sql = "SELECT * FROM users WHERE id = ?",
                selectionArgs = arrayOf(account.accountId.toString()),
            )
            val books = queryRows(
                database = database,
                sql = "SELECT * FROM books WHERE uid = ? ORDER BY id",
                selectionArgs = arrayOf(account.accountId.toString()),
            )
            val bookIds = books.mapNotNull { row ->
                (row.opt("id") as? Number)?.toLong()
            }
            val bookReadingRows = queryRows(
                database = database,
                sql = "SELECT * FROM book_reading WHERE uid = ? ORDER BY id",
                selectionArgs = arrayOf(account.accountId.toString()),
            )
            writeJsonEntry(zip, "database/users.json", rowsToJsonArray(userRows))
            writeJsonEntry(zip, "database/books.json", rowsToJsonArray(books))
            writeRowsForIdsEntry(
                zip = zip,
                path = "database/book_item_ref.json",
                database = database,
                table = "book_item_ref",
                idColumn = "book_id",
                ids = bookIds,
            )
            writeRowsForIdsEntry(
                zip = zip,
                path = "database/book_chapter.json",
                database = database,
                table = "book_chapter",
                idColumn = "book_id",
                ids = bookIds,
            )
            writeJsonEntry(zip, "database/book_reading.json", rowsToJsonArray(bookReadingRows))
        } finally {
            database.close()
        }
    }

    private fun loadAccountBookRows(activity: Activity, accountId: Long): List<JSONObject> {
        val databasePath = activity.applicationContext.getDatabasePath(HOST_DB_NAME)
        if (!databasePath.exists()) return emptyList()
        val database = SQLiteDatabase.openDatabase(
            databasePath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            queryRows(
                database = database,
                sql = "SELECT * FROM books WHERE uid = ? ORDER BY id",
                selectionArgs = arrayOf(accountId.toString()),
            )
        } finally {
            database.close()
        }
    }

    private fun writeUserFiles(zip: ZipOutputStream, activity: Activity, accountId: Long) {
        val root = File(activity.filesDir, accountId.toString())
        if (!root.exists()) return
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = root.toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace('\\', '/')
                addFileEntry(zip, file, "files/$accountId/$relativePath")
            }
    }

    private fun writeModuleGlobalEntries(zip: ZipOutputStream, activity: Activity) {
        val appContext = activity.applicationContext
        val prefsJson = JSONObject()
        MODULE_BACKUP_PREFS.forEach { prefsName ->
            val snapshot = SharedPrefsSnapshot.fromPreferences(
                appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
            )
            prefsJson.put(prefsName, snapshot.toJson())
        }
        writeJsonEntry(zip, "module/shared_prefs.json", prefsJson)

        val root = activity.filesDir
        MODULE_BACKUP_FILES.forEach { fileName ->
            val file = File(root, fileName)
            if (file.isFile) {
                addFileEntry(zip, file, "module/files/$fileName")
            }
        }
        MODULE_BACKUP_DIRS.forEach { dirName ->
            val dir = File(root, dirName)
            if (dir.isDirectory) {
                writeModuleDirectory(zip, dir, dirName)
            }
        }
    }

    private fun writeModuleDirectory(zip: ZipOutputStream, dir: File, dirName: String) {
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = dir.toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace('\\', '/')
                addFileEntry(zip, file, "module/files/$dirName/$relativePath")
            }
    }

    private fun writeAccountDataZip(output: OutputStream, activity: Activity, account: StoredAccount) {
        ZipOutputStream(output).use { zip ->
            writeJsonEntry(zip, "manifest.json", buildAccountExportManifest(activity, account))
            writeJsonEntry(zip, "account/stored_account.json", account.toJson())
            writeJsonEntry(zip, "account/session.json", account.session.toJson())
            writeJsonEntry(zip, "account/webdav_prefs.json", account.webDavPrefs.toJson())
            writeJsonEntry(zip, "account/local_library_prefs.json", account.localLibraryPrefs.toJson())
            writeJsonEntry(zip, LOCAL_TASK_BUNDLE_ENTRY,
                LocalTaskStore { activity.applicationContext }.exportAccountBundle(account.accountId.toString()))
            writeHostDatabaseEntries(zip, activity, account)
            writeUserFiles(zip, activity, account.accountId)
            writeModuleGlobalEntries(zip, activity)
        }
    }

    private fun normalizeModuleBackupPath(path: String): String? {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return null
        if (normalized.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return normalized
    }

    private fun isAllowedModuleBackupFile(relativePath: String): Boolean {
        if (relativePath in MODULE_BACKUP_FILES) return true
        val root = relativePath.substringBefore('/')
        return root in MODULE_BACKUP_DIRS && relativePath.contains('/')
    }

    private fun queryRows(database: SQLiteDatabase, sql: String, selectionArgs: Array<String>): List<JSONObject> =
        database.rawQuery(sql, selectionArgs).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursorRowToJson(cursor))
                }
            }
        }

    private fun cursorRowToJson(cursor: Cursor): JSONObject =
        JSONObject().apply {
            cursor.columnNames.forEachIndexed { index, columnName ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> put(columnName, JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> put(columnName, cursor.getLong(index))
                    Cursor.FIELD_TYPE_FLOAT -> put(columnName, cursor.getDouble(index))
                    Cursor.FIELD_TYPE_STRING -> put(columnName, cursor.getString(index))
                    Cursor.FIELD_TYPE_BLOB -> put(columnName, Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                    else -> put(columnName, cursor.getString(index))
                }
            }
        }

    private fun rowsToJsonArray(rows: List<JSONObject>): JSONArray =
        JSONArray().apply { rows.forEach(::put) }

    private fun writeJsonEntry(zip: ZipOutputStream, path: String, content: JSONObject) {
        writeTextEntry(zip, path, content.toString(2))
    }

    private fun writeJsonEntry(zip: ZipOutputStream, path: String, content: JSONArray) {
        writeTextEntry(zip, path, content.toString(2))
    }

    private fun writeTextEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeRowsForIdsEntry(
        zip: ZipOutputStream,
        path: String,
        database: SQLiteDatabase,
        table: String,
        idColumn: String,
        ids: List<Long>,
    ) {
        zip.putNextEntry(ZipEntry(path))
        var first = true
        zip.write("[".toByteArray(Charsets.UTF_8))
        ids.chunked(DATABASE_EXPORT_ID_CHUNK_SIZE).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT * FROM $table WHERE $idColumn IN ($placeholders) ORDER BY id",
                chunk.map(Long::toString).toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (!first) zip.write(",".toByteArray(Charsets.UTF_8))
                    zip.write(cursorRowToJson(cursor).toString().toByteArray(Charsets.UTF_8))
                    first = false
                }
            }
        }
        zip.write("]".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addFileEntry(zip: ZipOutputStream, file: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        BufferedInputStream(FileInputStream(file)).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun addBytesEntry(zip: ZipOutputStream, bytes: ByteArray, path: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeAccountBooksZip(
        output: OutputStream,
        account: StoredAccount,
        bookFilesCount: Int,
        exportedBooks: JSONArray,
        exportPlans: List<BookExportPlan>,
        tempDir: File,
    ) {
        ZipOutputStream(output).use { zip ->
            writeJsonEntry(
                zip,
                "manifest.json",
                JSONObject().apply {
                    put("type", "account_books_export")
                    put("format", 2)
                    put("accountId", account.accountId)
                    put("nickname", account.nickname)
                    put("identity", displayIdentity(account.email, account.thirdBinds))
                    put("exportedAt", System.currentTimeMillis())
                    put("fileCount", bookFilesCount)
                    put("bookCount", exportedBooks.length())
                    put("books", exportedBooks)
                },
            )
            exportPlans.forEach { plan ->
                val epubFile = buildEpubFile(plan.directory, tempDir)
                try {
                    addFileEntry(zip, epubFile, plan.entryName)
                } finally {
                    epubFile.delete()
                }
            }
        }
    }

    private fun buildEpubBytes(bookDir: File): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { epub -> writeEpubDirectory(bookDir, epub) }
            output.toByteArray()
        }

    private fun buildEpubFile(bookDir: File, tempDir: File): File {
        val outputFile = File.createTempFile("reamicro_book_", ".epub", tempDir)
        FileOutputStream(outputFile).buffered().use { output ->
            ZipOutputStream(output).use { epub -> writeEpubDirectory(bookDir, epub) }
        }
        return outputFile
    }

    private fun writeEpubDirectory(bookDir: File, epub: ZipOutputStream) {
        val mimetype = File(bookDir, "mimetype")
        if (mimetype.isFile) {
            addStoredFileEntry(epub, mimetype, "mimetype")
        }
        bookDir.walkTopDown()
            .filter { it.isFile && it != mimetype }
            .sortedBy { bookDir.toPath().relativize(it.toPath()).toString() }
            .forEach { file ->
                val relativePath = bookDir.toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace('\\', '/')
                val patchedOpf = if (relativePath.endsWith(".opf", ignoreCase = true)) {
                    patchedOpfBytes(file)
                } else {
                    null
                }
                if (patchedOpf != null) {
                    addBytesEntry(epub, patchedOpf, relativePath)
                } else {
                    addFileEntry(epub, file, relativePath)
                }
            }
    }

    private fun addStoredFileEntry(zip: ZipOutputStream, file: File, path: String) {
        val bytes = file.readBytes()
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun patchedOpfBytes(file: File): ByteArray? {
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val patched = ensureNcxSpineToc(content)
        return patched.takeIf { it != content }?.toByteArray(Charsets.UTF_8)
    }

    private fun ensureNcxSpineToc(content: String): String {
        if (Regex("""<spine\b[^>]*\btoc\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
            return content
        }
        val ncxId = Regex(
            """<item\b(?=[^>]*\bmedia-type\s*=\s*["']application/x-dtbncx\+xml["'])(?=[^>]*\bid\s*=\s*["']([^"']+)["'])[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(content)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return content
        return Regex("""<spine\b""", RegexOption.IGNORE_CASE)
            .replaceFirst(content, "<spine toc=\"$ncxId\"")
    }

    private fun uniqueBookArchiveEntryName(usedNames: MutableSet<String>, title: String): String {
        val cleanTitle = sanitizeArchiveFileName(title).ifBlank { "\u672a\u547d\u540d" }
        var index = 1
        while (true) {
            val suffix = if (index == 1) "" else " ($index)"
            val candidate = "books/$cleanTitle$suffix.epub"
            if (usedNames.add(candidate)) return candidate
            index += 1
        }
    }

    private fun sanitizeArchiveFileName(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trim('.')

    private fun buildAccountDataExportFileName(accountId: Long): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "阅微${accountId}_$timestamp.zip"
    }

    private fun buildAccountBooksExportFileName(accountId: Long): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "\u9605\u5fae${accountId}_\u5168\u90e8\u56fe\u4e66_$timestamp.zip"
    }

    private fun loadStoredAccounts(activity: Activity): List<StoredAccount> {
        val prefs = storePrefs(activity)
        val raw = prefs.getString(STORE_KEY_ACCOUNTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val account = StoredAccount.fromJson(item)
                    if (account.accountId > 0L) add(account)
                }
            }
        }.getOrElse {
            HookRuntime.log("$LOG_PREFIX failed to parse stored accounts: ${it.stackTraceToString()}")
            emptyList()
        }
    }

    private fun saveStoredAccounts(activity: Activity, accounts: List<StoredAccount>) {
        val array = JSONArray()
        accounts
            .filter { it.accountId > 0L }
            .sortedByDescending { it.lastUsedAt }
            .forEach { array.put(it.toJson()) }
        check(storePrefs(activity).edit().putString(STORE_KEY_ACCOUNTS, array.toString()).commit()) { "保存账号快照失败" }
    }

    private fun storePrefs(activity: Activity): SharedPreferences =
        activity.applicationContext.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)

    private fun encodePortableCredential(account: PortableCredential): String {
        val payload = account.toJson().apply {
            put("format", PORTABLE_FORMAT)
            put("type", "credential")
            put("exportedAt", System.currentTimeMillis())
        }
        val encoded = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$PORTABLE_PREFIX$encoded"
    }

    private fun decodePortableCredential(raw: String): StoredAccount {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) error("剪贴板中没有账号凭证")
        val jsonString = runCatching {
            when {
                trimmed.startsWith("{") -> trimmed
                trimmed.startsWith(PORTABLE_PREFIX) -> String(
                    Base64.decode(trimmed.removePrefix(PORTABLE_PREFIX), Base64.DEFAULT),
                    Charsets.UTF_8,
                )
                else -> String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
            }
        }.getOrElse {
            error("账号凭证格式不正确")
        }
        val json = runCatching { JSONObject(jsonString) }
            .getOrElse { error("账号凭证格式不正确") }
        return when {
            json.has("session") || json.has("localLibraryPrefs") -> StoredAccount.fromJson(json)
            else -> PortableCredential.fromJson(json).toStoredAccount()
        }.also(::validateStoredAccount)
    }

    private fun decodePortableCredentials(raw: String, depth: Int = 0): List<StoredAccount> {
        require(depth < 16) { "账号凭证嵌套过深" }
        val trimmed = raw.trim()
        if (trimmed.isBlank()) error("credential is empty")
        val jsonString = runCatching {
            when {
                trimmed.startsWith("{") -> trimmed
                trimmed.startsWith(PORTABLE_PREFIX) -> String(
                    Base64.decode(trimmed.removePrefix(PORTABLE_PREFIX), Base64.DEFAULT),
                    Charsets.UTF_8,
                )
                else -> String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
            }
        }.getOrElse {
            error("invalid credential format")
        }
        val json = runCatching { JSONObject(jsonString) }
            .getOrElse { error("invalid credential format") }
        return when {
            json.optString("type") == "credential_bundle" || json.has("accounts") -> {
                val array = json.optJSONArray("accounts") ?: error("invalid credential format")
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val credential = item.optString("credential").trim()
                        when {
                            credential.isNotBlank() -> addAll(decodePortableCredentials(credential, depth + 1))
                            item.has("session") || item.has("localLibraryPrefs") -> add(StoredAccount.fromJson(item))
                            item.has("token") && item.has("userData") -> add(PortableCredential.fromJson(item).toStoredAccount())
                        }
                    }
                }.distinctBy { it.accountId }
            }
            json.has("session") || json.has("localLibraryPrefs") -> listOf(StoredAccount.fromJson(json))
            else -> listOf(PortableCredential.fromJson(json).toStoredAccount())
        }.also { accounts -> accounts.forEach(::validateStoredAccount) }
    }

    private fun firstFlowValue(flow: Any): Any? = preferences.first(flow)

    private fun invokeSuspendMethod(method: Method, target: Any?, vararg args: Any?): Any? =
        com.reamicro.fix.core.HostCoroutines.invokeTimed(classLoader, method, target, SUSPEND_WAIT_TIMEOUT_MS, *args)

    private fun publishCachedUser(user: Any) {
        host.method("saveCache").invoke(null, host.constructor("cachedData").newInstance(user))
        check(host.field("cachedUser").get(null) == user) { "账户缓存写后回读不一致" }
    }

    // 宿主 users.data 是服务端账号资料的本地镜像，阅微冷启动会自行重新同步。
    // 因此已存在的账号行一律以宿主实时值为准，快照只补齐宿主缺失的字段，
    // 避免用陈旧快照把绑定关系、阅读时长等资料写回旧值。
    private fun guardUserDataAgainstHost(activity: Activity, account: StoredAccount): StoredAccount {
        val hostRow = runCatching { readHostUserRow(activity, account.accountId) }.getOrNull()
        val hostData = hostRow?.optString("data").orEmpty()
        if (hostData.isBlank() || hostData == account.userData) return account
        val merged = mergeUserDataJson(hostData, account.userData) ?: return account
        HookRuntime.log("$LOG_PREFIX host user data kept live for account ${account.accountId}")
        val liveBinds = userDataThirdBinds(merged)
        return account.copy(
            userData = merged,
            thirdBinds = liveBinds.ifEmpty { account.thirdBinds },
        )
    }

    private fun readHostUserRow(activity: Activity, accountId: Long): JSONObject? {
        val databasePath = activity.applicationContext.getDatabasePath(HOST_DB_NAME)
        if (!databasePath.exists()) return null
        val database = SQLiteDatabase.openDatabase(
            databasePath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            return queryRows(
                database = database,
                sql = "SELECT data, token FROM users WHERE id = ?",
                selectionArgs = arrayOf(accountId.toString()),
            ).firstOrNull()
        } finally {
            database.close()
        }
    }

    // 以宿主实时值为底，只补快照里存在而宿主缺失的字段；宿主已有的值（含 thirdBinds）保持不动。
    private fun mergeUserDataJson(hostData: String, snapshotData: String): String? {
        val host = runCatching { JSONObject(hostData) }.getOrNull() ?: return null
        val snapshot = runCatching { JSONObject(snapshotData) }.getOrNull() ?: return null
        host.put("id", accountAccountId(host, snapshot))
        val iterator = snapshot.keys()
        while (iterator.hasNext()) {
            val name = iterator.next()
            val hostValue = host.opt(name)
            if (hostValue != null && hostValue != JSONObject.NULL) continue
            val snapshotValue = snapshot.opt(name)
            if (snapshotValue == null || snapshotValue == JSONObject.NULL) continue
            host.put(name, snapshotValue)
        }
        return host.toString()
    }

    // 行主键永远跟随目标账号，防止导入包内的 id 把资料写到别人的行上。
    private fun accountAccountId(host: JSONObject, snapshot: JSONObject): Long {
        val hostId = host.optLong("id", 0L)
        return if (hostId > 0L) hostId else snapshot.optLong("id", 0L)
    }

    private fun userDataThirdBinds(userData: String): List<String> = runCatching {
        jsonArrayToStringList(JSONObject(userData).optJSONArray("thirdBinds"))
    }.getOrDefault(emptyList())

    private fun createHostUser(account: StoredAccount): Any = host.record("user", mapOf(
        "id" to account.accountId, "token" to account.token, "data" to account.userData,
        "updated" to System.currentTimeMillis(),
    ))
    private fun readPreference(prefs: Any, key: Any): Any? = preferences.method("read").invoke(prefs, key)
    @Suppress("UNUSED_PARAMETER")
    private fun key(className: String, getterName: String): Any =
        preferences.key(if (getterName == "getBUILD_IN_FONTS") "built_in_fonts" else getterName.removePrefix("get").lowercase(Locale.ROOT))
    private fun accountProperty(target: Any, name: String): Any? {
        val property = when (name) {
            "getId" -> "id"; "getData" -> "data"; "getToken" -> "token"; "getUpdated" -> "updated"
            "getNickName" -> "nickName"; "getEmail" -> "email"
            else -> error("Unknown account property: $name")
        }
        return when {
            host.type("user").isInstance(target) -> host.field("user.$property").get(target)
            host.type("info").isInstance(target) -> host.field("info.$property").get(target)
            preferences.type("session").isInstance(target) && property == "token" -> preferences.field("token").get(target)
            else -> error("Unexpected account record type: ${target.javaClass.name}")
        }
    }
    private fun Any.longValue(name: String): Long = (accountProperty(this, name) as? Number)?.toLong() ?: 0L
    private fun Any.stringValue(name: String): String = accountProperty(this, name)?.toString().orEmpty()

    private fun displayIdentity(email: String, thirdBinds: List<String>): String {
        if (email.isNotBlank()) return email
        val normalized = thirdBinds.map { it.lowercase() }
        val labels = buildList {
            if (normalized.any { "qq" in it }) add("QQ")
            if (normalized.any { "wechat" in it || it == "wx" }) add("微信")
        }
        return labels.joinToString("/").ifBlank { "未绑定" }
    }

    data class AccountDisplayItem(
        val accountId: Long,
        val nickname: String,
        val identity: String,
        val isCurrent: Boolean,
    )

    data class ExportedCredential(
        val account: AccountDisplayItem,
        val credential: String,
    )

    data class CurrentCloudCredential(
        val accountId: String,
        val token: String,
        val label: String,
    )

    data class ExportedCredentialBundle(
        val count: Int,
        val content: String,
    )

    data class ImportResult(
        val count: Int,
        val accounts: List<AccountDisplayItem>,
    )

    data class ExportedAccountDataBundle(
        val account: AccountDisplayItem,
        val fileName: String,
        val bytes: ByteArray,
    )

    data class ExportedAccountDataFileBundle(
        val account: AccountDisplayItem,
        val fileName: String,
        val file: File,
    )

    data class ExportedAccountBooksBundle(
        val account: AccountDisplayItem,
        val fileName: String,
        val bytes: ByteArray,
    )

    data class ExportedAccountBooksFileBundle(
        val account: AccountDisplayItem,
        val fileName: String,
        val file: File,
    )

    private data class BookExportPlan(
        val uuid: String,
        val title: String,
        val directory: File,
        val entryName: String,
    )

    data class ImportedAccountDataBundle(
        val account: AccountDisplayItem,
    )

    data class AccountDataBundlePreview(
        val accountId: Long,
        val nickname: String,
        val identity: String,
        val fileName: String,
        val exportedAt: Long,
        val fileCount: Int,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
        val archiveBytes: Long,
    val hasLocalTasks: Boolean = false,
    )

    private data class CurrentAccountPreview(
        val accountId: Long,
        val token: String,
        val userData: String,
        val nickname: String,
        val email: String,
        val thirdBinds: List<String>,
    ) {
        fun toStoredAccount(
            session: SessionSnapshot,
            webDavPrefs: SharedPrefsSnapshot,
            localLibraryPrefs: SharedPrefsSnapshot,
            hasFullState: Boolean,
        ): StoredAccount =
            StoredAccount(
                accountId = accountId,
                token = token,
                userData = userData,
                nickname = nickname,
                email = email,
                thirdBinds = thirdBinds,
                session = session,
                webDavPrefs = webDavPrefs,
                localLibraryPrefs = localLibraryPrefs,
                lastUsedAt = System.currentTimeMillis(),
                hasFullState = hasFullState,
            )

        fun toPlaceholderStoredAccount(): StoredAccount =
            toStoredAccount(
                session = SessionSnapshot.authOnly(
                    token = token,
                    baiduAuth = "",
                    aliyunAuth = "",
                    yun115Auth = "",
                ),
                webDavPrefs = SharedPrefsSnapshot.EMPTY,
                localLibraryPrefs = SharedPrefsSnapshot.EMPTY,
                hasFullState = false,
            )
    }

    private data class StoredAccount(
        val accountId: Long,
        val token: String,
        val userData: String,
        val nickname: String,
        val email: String,
        val thirdBinds: List<String>,
        val session: SessionSnapshot,
        val webDavPrefs: SharedPrefsSnapshot,
        val localLibraryPrefs: SharedPrefsSnapshot,
        val lastUsedAt: Long,
        val hasFullState: Boolean,
        val advancedPreferences: AccountAdvancedPreferences? = null,
    ) {
        fun toJson(): JSONObject =
            JSONObject().apply {
                put("accountId", accountId)
                put("token", token)
                put("userData", userData)
                put("nickname", nickname)
                put("email", email)
                put("thirdBinds", JSONArray(thirdBinds))
                put("session", session.toJson())
                put("webDavPrefs", webDavPrefs.toJson())
                put("localLibraryPrefs", localLibraryPrefs.toJson())
                put("lastUsedAt", lastUsedAt)
                put("hasFullState", hasFullState)
                advancedPreferences?.let { put("memberAdvancedPreferences", it.toJson()) }
            }

        fun toPortableCredential(): PortableCredential =
            PortableCredential(
                accountId = accountId,
                token = token,
                userData = userData,
                nickname = nickname,
                email = email,
                thirdBinds = thirdBinds,
                baiduAuth = session.baiduAuth,
                aliyunAuth = session.aliyunAuth,
                yun115Auth = session.yun115Auth,
                webDavPrefs = webDavPrefs.filterKeys(PORTABLE_WEBDAV_KEYS),
            )

        fun mergePreview(preview: CurrentAccountPreview): StoredAccount =
            copy(
                token = preview.token.ifBlank { token },
                userData = preview.userData.ifBlank { userData },
                nickname = preview.nickname.ifBlank { nickname },
                email = preview.email.ifBlank { email },
                thirdBinds = if (preview.thirdBinds.isNotEmpty()) preview.thirdBinds else thirdBinds,
                lastUsedAt = System.currentTimeMillis(),
            )

        fun mergeWith(other: StoredAccount): StoredAccount =
            copy(
                token = other.token.ifBlank { token },
                userData = other.userData.ifBlank { userData },
                nickname = other.nickname.ifBlank { nickname },
                email = other.email.ifBlank { email },
                thirdBinds = if (other.thirdBinds.isNotEmpty()) other.thirdBinds else thirdBinds,
                session = if (other.hasFullState || !hasFullState) other.session else session.mergeCredentialsFrom(other.session),
                webDavPrefs = if (other.hasFullState || !hasFullState) other.webDavPrefs else webDavPrefs.mergeKeysFrom(other.webDavPrefs, PORTABLE_WEBDAV_KEYS),
                localLibraryPrefs = if (other.hasFullState || !hasFullState) other.localLibraryPrefs else localLibraryPrefs,
                lastUsedAt = maxOf(lastUsedAt, other.lastUsedAt),
                hasFullState = hasFullState || other.hasFullState,
                advancedPreferences = other.advancedPreferences ?: advancedPreferences,
            )

        companion object {
            fun fromJson(json: JSONObject): StoredAccount =
                StoredAccount(
                    accountId = json.optLong("accountId"),
                    token = json.optString("token"),
                    userData = json.optString("userData"),
                    nickname = json.optString("nickname"),
                    email = json.optString("email"),
                    thirdBinds = jsonArrayToStringList(json.optJSONArray("thirdBinds")),
                    session = SessionSnapshot.fromJson(json.optJSONObject("session")),
                    webDavPrefs = SharedPrefsSnapshot.fromJson(json.optJSONObject("webDavPrefs")),
                    localLibraryPrefs = SharedPrefsSnapshot.fromJson(json.optJSONObject("localLibraryPrefs")),
                    lastUsedAt = json.optLong("lastUsedAt", System.currentTimeMillis()),
                    hasFullState = json.optBoolean(
                        "hasFullState",
                        json.has("session") || json.has("localLibraryPrefs"),
                    ),
                    advancedPreferences = accountAdvancedPreferencesFromJson(json.optJSONObject("memberAdvancedPreferences")),
                )
        }
    }

    private data class PortableCredential(
        val accountId: Long,
        val token: String,
        val userData: String,
        val nickname: String,
        val email: String,
        val thirdBinds: List<String>,
        val baiduAuth: String,
        val aliyunAuth: String,
        val yun115Auth: String,
        val webDavPrefs: SharedPrefsSnapshot,
    ) {
        fun toJson(): JSONObject =
            JSONObject().apply {
                put("accountId", accountId)
                put("token", token)
                put("userData", userData)
                put("nickname", nickname)
                put("email", email)
                put("thirdBinds", JSONArray(thirdBinds))
                put("baiduAuth", baiduAuth)
                put("aliyunAuth", aliyunAuth)
                put("yun115Auth", yun115Auth)
                put("webDavPrefs", webDavPrefs.toJson())
            }

        fun toStoredAccount(): StoredAccount {
            if (accountId <= 0L || token.isBlank() || userData.isBlank()) {
                error("账号凭证内容不完整")
            }
            return StoredAccount(
                accountId = accountId,
                token = token,
                userData = userData,
                nickname = nickname,
                email = email,
                thirdBinds = thirdBinds,
                session = SessionSnapshot.authOnly(token, baiduAuth, aliyunAuth, yun115Auth),
                webDavPrefs = webDavPrefs.filterKeys(PORTABLE_WEBDAV_KEYS),
                localLibraryPrefs = SharedPrefsSnapshot.EMPTY,
                lastUsedAt = System.currentTimeMillis(),
                hasFullState = false,
            )
        }

        companion object {
            fun fromJson(json: JSONObject): PortableCredential =
                PortableCredential(
                    accountId = json.optLong("accountId"),
                    token = json.optString("token"),
                    userData = json.optString("userData"),
                    nickname = json.optString("nickname"),
                    email = json.optString("email"),
                    thirdBinds = jsonArrayToStringList(json.optJSONArray("thirdBinds")),
                    baiduAuth = json.optString("baiduAuth"),
                    aliyunAuth = json.optString("aliyunAuth"),
                    yun115Auth = json.optString("yun115Auth"),
                    webDavPrefs = SharedPrefsSnapshot.fromJson(json.optJSONObject("webDavPrefs")),
                )
        }
    }

    private data class SessionSnapshot(
        val token: String,
        val dynamicColor: Boolean,
        val darkMode: Int,
        val backupType: Int,
        val bookshelfType: Int,
        val theme: String,
        val mipmap: String,
        val background: String,
        val textSize: Float,
        val lineHeight: Float,
        val family: String,
        val embeddedFonts: Boolean,
        val builtInFonts: Boolean,
        val padding: Int,
        val readerMargins: ReaderMarginsSnapshot? = null,
        val onlyNextPage: Boolean,
        val volumeKey: Boolean,
        val timeBattery: Boolean,
        val screenAlwaysOn: Boolean,
        val systemBars: Boolean,
        val agreement: Boolean,

        val smartRecognition: Boolean,
        val illustrationOptimization: Boolean,
        val relateDownloadCover: Boolean,
        val callingCardColor: Boolean,
        val baiduAuth: String,
        val aliyunAuth: String,
        val yun115Auth: String,
        val syncPreferences: AccountSyncPreferences? = null,
    ) {
        fun advanced(): AccountAdvancedPreferences = AccountAdvancedPreferences(
            dynamicColor, smartRecognition, illustrationOptimization, relateDownloadCover, callingCardColor,
        )

        fun toJson(): JSONObject =
            JSONObject().apply {
                put("token", token)
                put("dynamicColor", dynamicColor)
                put("darkMode", darkMode)
                put("backupType", backupType)
                put("bookshelfType", bookshelfType)
                put("theme", theme)
                put("mipmap", mipmap)
                put("background", background)
                put("textSize", textSize.toDouble())
                put("lineHeight", lineHeight.toDouble())
                put("family", family)
                put("embeddedFonts", embeddedFonts)
                put("builtInFonts", builtInFonts)
                put("padding", padding)
                readerMargins?.let { put("readerMargins", it.toJson()) }
                put("onlyNextPage", onlyNextPage)
                put("volumeKey", volumeKey)
                put("timeBattery", timeBattery)
                put("screenAlwaysOn", screenAlwaysOn)
                put("systemBars", systemBars)
                put("agreement", agreement)
                put("smartRecognition", smartRecognition)
                put("illustrationOptimization", illustrationOptimization)
                put("relateDownloadCover", relateDownloadCover)
                put("callingCardColor", callingCardColor)
                put("baiduAuth", baiduAuth)
                put("aliyunAuth", aliyunAuth)
                put("yun115Auth", yun115Auth)
                syncPreferences?.let { put("syncPreferences", it.toJson()) }
            }

        fun mergeCredentialsFrom(other: SessionSnapshot): SessionSnapshot =
            copy(
                token = other.token.ifBlank { token },
                baiduAuth = other.baiduAuth.ifBlank { baiduAuth },
                aliyunAuth = other.aliyunAuth.ifBlank { aliyunAuth },
                yun115Auth = other.yun115Auth.ifBlank { yun115Auth },
            )

        companion object {
            fun empty(token: String): SessionSnapshot =
                SessionSnapshot(
                    token = token,
                    dynamicColor = false,
                    darkMode = 0,
                    backupType = 0,
                    bookshelfType = 0,
                    theme = "",
                    mipmap = "",
                    background = "",
                    textSize = 0f,
                    lineHeight = 0f,
                    family = "",
                    embeddedFonts = true,
                    builtInFonts = false,
                    padding = 0,
                    onlyNextPage = false,
                    volumeKey = false,
                    timeBattery = false,
                    screenAlwaysOn = false,
                    systemBars = false,
                    agreement = false,
                    smartRecognition = false,
                    illustrationOptimization = false,
                    relateDownloadCover = false,
                    callingCardColor = false,
                    baiduAuth = "",
                    aliyunAuth = "",
                    yun115Auth = "",
                )

            fun authOnly(
                token: String,
                baiduAuth: String,
                aliyunAuth: String,
                yun115Auth: String,
            ): SessionSnapshot =
                empty(token).copy(
                    baiduAuth = baiduAuth,
                    aliyunAuth = aliyunAuth,
                    yun115Auth = yun115Auth,
                )

            fun fromJson(json: JSONObject?): SessionSnapshot {
                val source = json ?: return empty("")
                return SessionSnapshot(
                    token = source.optString("token"),
                    dynamicColor = source.optBoolean("dynamicColor", false),
                    darkMode = source.optInt("darkMode", 0),
                    backupType = source.optInt("backupType", 0),
                    bookshelfType = source.optInt("bookshelfType", 0),
                    theme = source.optString("theme"),
                    mipmap = source.optString("mipmap"),
                    background = source.optString("background"),
                    textSize = source.optDouble("textSize", 0.0).toFloat(),
                    lineHeight = source.optDouble("lineHeight", 0.0).toFloat(),
                    family = source.optString("family"),
                    embeddedFonts = source.optBoolean("embeddedFonts", true),
                    builtInFonts = source.optBoolean("builtInFonts", false),
                    padding = source.optInt("padding", 0),
                    readerMargins = source.optJSONObject("readerMargins")?.let {
                        ReaderMarginsSnapshot.fromJson(it, source.optInt("padding", 0))
                    },
                    onlyNextPage = source.optBoolean("onlyNextPage", false),
                    volumeKey = source.optBoolean("volumeKey", false),
                    timeBattery = source.optBoolean("timeBattery", false),
                    screenAlwaysOn = source.optBoolean("screenAlwaysOn", false),
                    systemBars = source.optBoolean("systemBars", false),
                    agreement = source.optBoolean("agreement", false),
                    smartRecognition = source.optBoolean("smartRecognition", false),
                    illustrationOptimization = source.optBoolean("illustrationOptimization", false),
                    relateDownloadCover = source.optBoolean("relateDownloadCover", false),
                    callingCardColor = source.optBoolean("callingCardColor", false),
                    baiduAuth = source.optString("baiduAuth"),
                    aliyunAuth = source.optString("aliyunAuth"),
                    yun115Auth = source.optString("yun115Auth"),
                    syncPreferences = AccountSyncPreferences.fromJson(source.optJSONObject("syncPreferences")),
                )
            }
        }
    }

    private data class SharedPrefsSnapshot(
        val entries: Map<String, PreferenceValue>,
    ) {
        fun toJson(): JSONObject =
            JSONObject().apply {
                entries.forEach { (key, value) -> put(key, value.toJson()) }
            }

        fun applyTo(editor: SharedPreferences.Editor) {
            entries.forEach { (key, value) ->
                when (value) {
                    is PreferenceValue.StringValue -> editor.putString(key, value.value)
                    is PreferenceValue.BooleanValue -> editor.putBoolean(key, value.value)
                    is PreferenceValue.IntValue -> editor.putInt(key, value.value)
                    is PreferenceValue.LongValue -> editor.putLong(key, value.value)
                    is PreferenceValue.FloatValue -> editor.putFloat(key, value.value)
                    is PreferenceValue.StringSetValue -> editor.putStringSet(key, value.value.toSet())
                }
            }
        }

        fun filterKeys(keys: Set<String>): SharedPrefsSnapshot =
            SharedPrefsSnapshot(entries.filterKeys { it in keys })

        fun mergeKeysFrom(other: SharedPrefsSnapshot, keys: Set<String>): SharedPrefsSnapshot {
            if (other.entries.isEmpty()) return this
            val merged = entries.toMutableMap()
            keys.forEach { key ->
                val value = other.entries[key] ?: return@forEach
                merged[key] = value
            }
            return SharedPrefsSnapshot(merged)
        }

        companion object {
            val EMPTY = SharedPrefsSnapshot(emptyMap())

            fun fromPreferences(prefs: SharedPreferences): SharedPrefsSnapshot =
                SharedPrefsSnapshot(
                    prefs.all.mapNotNull { (key, value) ->
                        PreferenceValue.fromAny(value)?.let { key to it }
                    }.toMap(),
                )

            fun fromJson(json: JSONObject?): SharedPrefsSnapshot {
                if (json == null) return EMPTY
                val entries = linkedMapOf<String, PreferenceValue>()
                val iterator = json.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val value = PreferenceValue.fromJson(json.optJSONObject(key)) ?: continue
                    entries[key] = value
                }
                return SharedPrefsSnapshot(entries)
            }
        }
    }

    private sealed class PreferenceValue {
        abstract fun toJson(): JSONObject

        data class StringValue(val value: String) : PreferenceValue() {
            override fun toJson(): JSONObject =
                JSONObject().put("type", "string").put("value", value)
        }

        data class BooleanValue(val value: Boolean) : PreferenceValue() {
            override fun toJson(): JSONObject =
                JSONObject().put("type", "boolean").put("value", value)
        }

        data class IntValue(val value: Int) : PreferenceValue() {
            override fun toJson(): JSONObject =
                JSONObject().put("type", "int").put("value", value)
        }

        data class LongValue(val value: Long) : PreferenceValue() {
            override fun toJson(): JSONObject =
                JSONObject().put("type", "long").put("value", value)
        }

        data class FloatValue(val value: Float) : PreferenceValue() {
            override fun toJson(): JSONObject =
                JSONObject().put("type", "float").put("value", value.toDouble())
        }

        data class StringSetValue(val value: List<String>) : PreferenceValue() {
            override fun toJson(): JSONObject =
                JSONObject().put("type", "string_set").put("value", JSONArray(value))
        }

        companion object {
            fun fromAny(value: Any?): PreferenceValue? =
                when (value) {
                    is String -> StringValue(value)
                    is Boolean -> BooleanValue(value)
                    is Int -> IntValue(value)
                    is Long -> LongValue(value)
                    is Float -> FloatValue(value)
                    is Set<*> -> StringSetValue(value.mapNotNull { it?.toString() })
                    else -> null
                }

            fun fromJson(json: JSONObject?): PreferenceValue? {
                val source = json ?: return null
                return when (source.optString("type")) {
                    "string" -> StringValue(source.optString("value"))
                    "boolean" -> BooleanValue(source.optBoolean("value", false))
                    "int" -> IntValue(source.optInt("value", 0))
                    "long" -> LongValue(source.optLong("value", 0L))
                    "float" -> FloatValue(source.optDouble("value", 0.0).toFloat())
                    "string_set" -> StringSetValue(jsonArrayToStringList(source.optJSONArray("value")))
                    else -> null
                }
            }
        }
    }

}

private fun jsonArrayToStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) add(value)
        }
    }
}
