package com.reamicro.fix.hook

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.Base64
import de.robv.android.xposed.XposedBridge
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

private const val LOG_PREFIX = "ReaMicro LSP"
private const val STORE_PREFS = "reamicro_fix_account_store"
private const val STORE_KEY_ACCOUNTS = "accounts"
private const val PORTABLE_PREFIX = "REAMICRO_ACCOUNT_1:"
private const val PORTABLE_FORMAT = 1
private const val HOST_DB_NAME = "app.db"
private const val HOST_USER_CLASS = "app.zhendong.reamicro.data.db.entity.User"
private const val HOST_USER_DAO_CLASS = "app.zhendong.reamicro.data.db.dao.UserDao"
private const val CACHED_CLASS = "app.zhendong.reamicro.repository.core.Cached"
private const val SESSION_CLASS = "app.zhendong.reamicro.repository.core.Session"
private const val USER_REPOSITORY_CLASS = "app.zhendong.reamicro.repository.UserRepository"
private const val PREF_KEYS_CLASS = "app.zhendong.reamicro.constants.PrefKeys"
private const val THIRD_PARTY_KEYS_CLASS = "app.zhendong.reamicro.data.third.ThirdPartyKeys"
private const val FLOW_KT_CLASS = "kotlinx.coroutines.flow.FlowKt"
private const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
private const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
private const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
private const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
private const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
private const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
private const val ANDROID_KOIN_SCOPE_EXT_CLASS = "org.koin.android.ext.android.AndroidKoinScopeExtKt"
private const val KOTLIN_REFLECTION_CLASS = "kotlin.jvm.internal.Reflection"
private const val WEBDAV_PREFS = "reamicro_fix_webdav"
private const val LOCAL_LIBRARY_PREFS = "reamicro_fix_local_library"
private const val MODULE_SETTINGS_PREFS = "reamicro_fix_module_settings"
private const val READER_AUTO_PAGE_PREFS = "reamicro_reader_auto_page"
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

private val PORTABLE_WEBDAV_KEYS = setOf(
    WEBDAV_KEY_URL,
    WEBDAV_KEY_USERNAME,
    WEBDAV_KEY_PASSWORD,
    WEBDAV_KEY_AUTHORIZED,
)

private val MODULE_BACKUP_PREFS = setOf(
    MODULE_SETTINGS_PREFS,
    READER_AUTO_PAGE_PREFS,
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
        val activity = activityProvider() ?: error("当前没有可用页面")
        val account = captureCurrentAccount(activity) ?: error("当前未登录阅微账号")
        val accounts = loadStoredAccounts(activity)
            .associateByTo(linkedMapOf()) { it.accountId }
        val normalized = account.copy(lastUsedAt = System.currentTimeMillis())
        accounts[normalized.accountId] = normalized
        saveStoredAccounts(activity, accounts.values.toList())
        return ExportedCredential(
            account = AccountDisplayItem(
                accountId = normalized.accountId,
                nickname = normalized.nickname,
                identity = displayIdentity(normalized.email, normalized.thirdBinds),
                isCurrent = true,
            ),
            credential = encodePortableCredential(normalized.toPortableCredential()),
        )
    }

    fun exportAllCredentialsBundle(): ExportedCredentialBundle {
        val activity = activityProvider() ?: error("褰撳墠娌℃湁鍙敤椤甸潰")
        val accounts = loadStoredAccounts(activity)
            .associateByTo(linkedMapOf()) { it.accountId }
        captureCurrentAccount(activity)?.copy(lastUsedAt = System.currentTimeMillis())?.let { snapshot ->
            accounts[snapshot.accountId] = snapshot
        }
        if (accounts.isEmpty()) error("褰撳墠娌℃湁鍙鍑虹殑璐﹀彿")
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
        return ExportedCredentialBundle(
            count = sortedAccounts.size,
            content = payload.toString(2),
        )
    }

    fun importCredential(raw: String): AccountDisplayItem {
        val activity = activityProvider() ?: error("当前没有可用页面")
        val imported = decodePortableCredential(raw).copy(lastUsedAt = System.currentTimeMillis())
        val accounts = loadStoredAccounts(activity)
            .associateByTo(linkedMapOf()) { it.accountId }
        val existing = accounts[imported.accountId]
        accounts[imported.accountId] = existing?.mergeWith(imported) ?: imported
        saveStoredAccounts(activity, accounts.values.toList())
        val currentId = currentAccountPreview(activity)?.accountId
        return AccountDisplayItem(
            accountId = imported.accountId,
            nickname = imported.nickname,
            identity = displayIdentity(imported.email, imported.thirdBinds),
            isCurrent = imported.accountId == currentId,
        )
    }

    fun importCredentials(raw: String): ImportResult {
        val activity = activityProvider() ?: error("褰撳墠娌℃湁鍙敤椤甸潰")
        val importedAccounts = decodePortableCredentials(raw)
        if (importedAccounts.isEmpty()) error("鏈鍙栧埌鍙鍏ョ殑璐﹀彿鍑瘉")
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
        return ImportResult(
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

    fun switchToAccount(accountId: Long): AccountDisplayItem {
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

    fun persistCurrentAccountSnapshot(): Boolean {
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
        val activity = activityProvider() ?: error("褰撳墠娌℃湁鍙敤椤甸潰")
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
        if (accountIds.isEmpty()) error("鏆傛棤鍙鍑鸿处鍙?")
        persistCurrentAccountSnapshot()
        return accountIds.map(::exportAccountDataBundle)
    }

    fun exportAccountDataBundleFile(accountId: Long): ExportedAccountDataFileBundle {
        val activity = activityProvider() ?: error("褰撳墠娌℃湁鍙敤椤甸潰")
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
        if (accountIds.isEmpty()) error("鏆傛棤鍙鍑鸿处鍙?")
        persistCurrentAccountSnapshot()
        return accountIds.map(::exportAccountDataBundleFile)
    }

    fun exportAccountBooksBundle(accountId: Long): ExportedAccountBooksBundle {
        val activity = activityProvider() ?: error("褰撳墠娌℃湁鍙敤椤甸潰")
        val account = resolveAccountForDataExport(activity, accountId)
        val root = File(activity.filesDir, "${account.accountId}/books")
        if (!root.exists() || !root.isDirectory) error("璇ヨ处鍙锋殏鏃犲浘涔?")
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
        if (bookFiles.isEmpty()) error("璇ヨ处鍙锋殏鏃犲浘涔?")
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
        val activity = activityProvider() ?: error("褰撳墠娌℃湁鍙敤椤甸潰")
        val account = resolveAccountForDataExport(activity, accountId)
        val root = File(activity.filesDir, "${account.accountId}/books")
        if (!root.exists() || !root.isDirectory) error("璇ヨ处鍙锋殏鏃犲浘涔?")
        val bookFiles = root.walkTopDown().filter { it.isFile }.toList()
        val bookDirs = root.listFiles()
            ?.filter { it.isDirectory && it.walkTopDown().any(File::isFile) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (bookFiles.isEmpty() || bookDirs.isEmpty()) error("璇ヨ处鍙锋殏鏃犲浘涔?")
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

    fun importAccountDataBundle(bytes: ByteArray): ImportedAccountDataBundle {
        val activity = activityProvider() ?: error("褰撳墠娌℃湁鍙敤椤甸潰")
        persistCurrentAccountSnapshot()
        val bundle = decodeAccountDataBundle(bytes)
        restoreAccountDataBundle(activity, bundle)
        val normalized = bundle.account.copy(lastUsedAt = System.currentTimeMillis())
        restoreAccount(activity, normalized)
        val stored = loadStoredAccounts(activity)
            .associateByTo(linkedMapOf()) { it.accountId }
        val existing = stored[normalized.accountId]
        stored[normalized.accountId] = existing?.mergeWith(normalized) ?: normalized
        saveStoredAccounts(activity, stored.values.toList())
        return ImportedAccountDataBundle(
            account = AccountDisplayItem(
                accountId = normalized.accountId,
                nickname = normalized.nickname,
                identity = displayIdentity(normalized.email, normalized.thirdBinds),
                isCurrent = true,
            ),
        )
    }

    private fun captureCurrentAccount(activity: Activity): StoredAccount? {
        val current = currentAccountPreview(activity) ?: return null
        val session = currentSession(activity) ?: return null
        return current.toStoredAccount(
            session = captureSessionSnapshot(session),
            webDavPrefs = captureSharedPrefsSnapshot(activity, WEBDAV_PREFS),
            localLibraryPrefs = captureSharedPrefsSnapshot(activity, LOCAL_LIBRARY_PREFS),
            hasFullState = true,
        )
    }

    private fun currentAccountPreview(activity: Activity): CurrentAccountPreview? {
        val currentUser = currentCachedUser(activity) ?: return null
        val accountId = currentUser.longValue("getId")
        val userData = currentUser.stringValue("getData")
        val userToken = currentUser.stringValue("getToken")
        if (accountId <= 0L || (userToken.isBlank() && userData.isBlank())) return null
        val info = runCatching { method0(currentUser, "info") }.getOrNull()
        val nickname = info?.let { it.stringValue("getNickName") }.orEmpty()
        val email = info?.let { it.stringValue("getEmail") }.orEmpty()
        val thirdBinds = (info?.let { method0(it, "getThirdBinds") } as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val sessionToken = currentSession(activity)
            ?.let { runCatching { method0(it, "getToken") as? String }.getOrNull() }
            .orEmpty()
        return CurrentAccountPreview(
            accountId = accountId,
            token = sessionToken.ifBlank { userToken },
            userData = userData,
            nickname = nickname,
            email = email,
            thirdBinds = thirdBinds,
        )
    }

    private fun restoreAccount(activity: Activity, account: StoredAccount) {
        upsertUserRow(activity, account)
        val session = currentSession(activity) ?: error("无法获取阅微会话")
        method1(session, "setToken", account.token)
        updatePreference(session, key(PREF_KEYS_CLASS, "getTOKEN"), account.session.token)
        updatePreference(session, key(THIRD_PARTY_KEYS_CLASS, "getBAIDU_AUTH"), account.session.baiduAuth)
        updatePreference(session, key(THIRD_PARTY_KEYS_CLASS, "getALIYUN_AUTH"), account.session.aliyunAuth)
        updatePreference(session, key(THIRD_PARTY_KEYS_CLASS, "getYUN115_AUTH"), account.session.yun115Auth)
        if (account.hasFullState) {
            restoreFullSessionPreferences(session, account.session)
            restoreSharedPrefs(activity, WEBDAV_PREFS, account.webDavPrefs)
            restoreSharedPrefs(activity, LOCAL_LIBRARY_PREFS, account.localLibraryPrefs)
        } else {
            restoreSharedPrefs(activity, WEBDAV_PREFS, account.webDavPrefs)
            restoreSharedPrefs(activity, LOCAL_LIBRARY_PREFS, SharedPrefsSnapshot.EMPTY)
        }
        updateCachedUser(account)
        method1(session, "setToken", account.token)
    }

    private fun restoreFullSessionPreferences(session: Any, snapshot: SessionSnapshot) {
        updatePreference(session, key(PREF_KEYS_CLASS, "getDYNAMIC_COLOR"), snapshot.dynamicColor)
        updatePreference(session, key(PREF_KEYS_CLASS, "getDARK_MODE"), snapshot.darkMode)
        updatePreference(session, key(PREF_KEYS_CLASS, "getBACKUP_TYPE"), snapshot.backupType)
        updatePreference(session, key(PREF_KEYS_CLASS, "getBOOKSHELF_TYPE"), snapshot.bookshelfType)
        updatePreference(session, key(PREF_KEYS_CLASS, "getTHEME"), snapshot.theme)
        updatePreference(session, key(PREF_KEYS_CLASS, "getMIPMAP"), snapshot.mipmap)
        updatePreference(session, key(PREF_KEYS_CLASS, "getBACKGROUND"), snapshot.background)
        updatePreference(session, key(PREF_KEYS_CLASS, "getTEXT_SIZE"), snapshot.textSize)
        updatePreference(session, key(PREF_KEYS_CLASS, "getLINE_HEIGHT"), snapshot.lineHeight)
        updatePreference(session, key(PREF_KEYS_CLASS, "getFAMILY"), snapshot.family)
        updatePreference(session, key(PREF_KEYS_CLASS, "getEMBEDDED_FONTS"), snapshot.embeddedFonts)
        updatePreference(session, key(PREF_KEYS_CLASS, "getBUILD_IN_FONTS"), snapshot.builtInFonts)
        updatePreference(session, key(PREF_KEYS_CLASS, "getPADDING"), snapshot.padding)
        updatePreference(session, key(PREF_KEYS_CLASS, "getONLY_NEXT_PAGE"), snapshot.onlyNextPage)
        updatePreference(session, key(PREF_KEYS_CLASS, "getVOLUME_KEY"), snapshot.volumeKey)
        updatePreference(session, key(PREF_KEYS_CLASS, "getTIME_BATTERY"), snapshot.timeBattery)
        updatePreference(session, key(PREF_KEYS_CLASS, "getSCREEN_ALWAYS_ON"), snapshot.screenAlwaysOn)
        updatePreference(session, key(PREF_KEYS_CLASS, "getSYSTEM_BARS"), snapshot.systemBars)
        updatePreference(session, key(PREF_KEYS_CLASS, "getAGREEMENT"), snapshot.agreement)
    }

    private fun captureSessionSnapshot(session: Any): SessionSnapshot {
        val dataStore = method0(session, "getDataStore")
        val dataFlow = method0(dataStore, "getData")
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
            textSize = (readPreference(prefs, key(PREF_KEYS_CLASS, "getTEXT_SIZE")) as? Number)?.toFloat() ?: 0f,
            lineHeight = (readPreference(prefs, key(PREF_KEYS_CLASS, "getLINE_HEIGHT")) as? Number)?.toFloat() ?: 0f,
            family = readPreference(prefs, key(PREF_KEYS_CLASS, "getFAMILY")) as? String ?: "",
            embeddedFonts = readPreference(prefs, key(PREF_KEYS_CLASS, "getEMBEDDED_FONTS")) as? Boolean ?: true,
            builtInFonts = readPreference(prefs, key(PREF_KEYS_CLASS, "getBUILD_IN_FONTS")) as? Boolean ?: false,
            padding = (readPreference(prefs, key(PREF_KEYS_CLASS, "getPADDING")) as? Number)?.toInt() ?: 0,
            onlyNextPage = readPreference(prefs, key(PREF_KEYS_CLASS, "getONLY_NEXT_PAGE")) as? Boolean ?: false,
            volumeKey = readPreference(prefs, key(PREF_KEYS_CLASS, "getVOLUME_KEY")) as? Boolean ?: false,
            timeBattery = readPreference(prefs, key(PREF_KEYS_CLASS, "getTIME_BATTERY")) as? Boolean ?: false,
            screenAlwaysOn = readPreference(prefs, key(PREF_KEYS_CLASS, "getSCREEN_ALWAYS_ON")) as? Boolean ?: false,
            systemBars = readPreference(prefs, key(PREF_KEYS_CLASS, "getSYSTEM_BARS")) as? Boolean ?: false,
            agreement = readPreference(prefs, key(PREF_KEYS_CLASS, "getAGREEMENT")) as? Boolean ?: false,
            baiduAuth = readPreference(prefs, key(THIRD_PARTY_KEYS_CLASS, "getBAIDU_AUTH")) as? String ?: "",
            aliyunAuth = readPreference(prefs, key(THIRD_PARTY_KEYS_CLASS, "getALIYUN_AUTH")) as? String ?: "",
            yun115Auth = readPreference(prefs, key(THIRD_PARTY_KEYS_CLASS, "getYUN115_AUTH")) as? String ?: "",
        )
    }

    private fun captureSharedPrefsSnapshot(activity: Activity, prefsName: String): SharedPrefsSnapshot =
        SharedPrefsSnapshot.fromPreferences(
            activity.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        )

    private fun restoreSharedPrefs(activity: Activity, prefsName: String, snapshot: SharedPrefsSnapshot) {
        val prefs = activity.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val editor = prefs.edit().clear()
        snapshot.applyTo(editor)
        editor.commit()
    }

    private fun currentSession(activity: Activity): Any? =
        runCatching {
            sessionFromActivity(activity)
                ?: sessionFromKoin(activity)
        }.getOrNull()

    private fun currentCachedUser(activity: Activity): Any? =
        runCatching {
            val cached = staticObject(CACHED_CLASS, "INSTANCE")
            val cachedUser = cached?.let { method0(it, "getCurrentUser") }
            cachedUser ?: currentUserFromKoin(activity)
        }.getOrNull()

    private fun sessionFromActivity(activity: Activity): Any? =
        runCatching {
            val repository = method0(activity, "getUserRepository")
            method0(repository, "getSession")
        }.getOrNull()

    private fun sessionFromKoin(activity: Activity): Any? =
        koinGet(activity, SESSION_CLASS)
            ?: koinGet(activity, USER_REPOSITORY_CLASS)?.let { repository ->
                runCatching { method0(repository, "getSession") }.getOrNull()
            }

    private fun currentUserFromKoin(activity: Activity): Any? =
        koinGet(activity, USER_REPOSITORY_CLASS)?.let { repository ->
            runCatching {
                val flow = method0(repository, "getCurrentUserFlow")
                firstFlowValue(flow)
            }.getOrNull()
        }

    private fun koinGet(activity: Activity, className: String): Any? =
        runCatching {
            val scopeProvider = Class.forName(ANDROID_KOIN_SCOPE_EXT_CLASS, false, classLoader)
            val getScope = scopeProvider.methods.firstOrNull {
                it.name == "getKoinScope" && it.parameterTypes.size == 1
            } ?: return@runCatching null
            val scope = getScope.invoke(null, activity) ?: return@runCatching null
            val reflection = Class.forName(KOTLIN_REFLECTION_CLASS, false, classLoader)
            val getKClass = reflection.methods.firstOrNull {
                it.name == "getOrCreateKotlinClass" && it.parameterTypes.size == 1
            } ?: return@runCatching null
            val targetClass = Class.forName(className, false, classLoader)
            val kClass = getKClass.invoke(null, targetClass)
            scope.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 3
            }?.invoke(scope, kClass, null, null)
        }.getOrNull()

    private fun resolveAccountForDataExport(activity: Activity, accountId: Long): StoredAccount {
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
                return merged
            }
        }
        return stored[accountId] ?: error("鏈壘鍒拌处鍙峰嚟璇?")
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

    private fun decodeAccountDataBundle(bytes: ByteArray): DecodedAccountDataBundle {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        val manifestBytes = entries["manifest.json"] ?: error("缂烘皯瀵艰緭娓呭崟")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        if (manifest.optString("type") != "account_data_export") {
            error("涓嶆敮鎸佺殑鏁版嵁鍖呮牸寮?")
        }
        val storedBytes = entries["account/stored_account.json"] ?: error("缂哄皯璐﹀彿鏁版嵁")
        val account = StoredAccount.fromJson(JSONObject(String(storedBytes, Charsets.UTF_8)))
        if (account.accountId <= 0L) error("璐﹀彿鏁版嵁鏃犳晥")
        val dbEntries = DatabaseEntries(
            users = decodeJsonArray(entries["database/users.json"]),
            books = decodeJsonArray(entries["database/books.json"]),
            bookItemRefs = decodeJsonArray(entries["database/book_item_ref.json"]),
            bookChapters = decodeJsonArray(entries["database/book_chapter.json"]),
            bookReading = decodeJsonArray(entries["database/book_reading.json"]),
        )
        val filePrefix = "files/${account.accountId}/"
        val files = entries.entries
            .asSequence()
            .filter { it.key.startsWith(filePrefix) }
            .associate { (path, content) -> path.removePrefix(filePrefix) to content }
        val moduleEntries = decodeModuleGlobalEntries(entries)
        return DecodedAccountDataBundle(
            manifest = manifest,
            account = account,
            database = dbEntries,
            files = files,
            module = moduleEntries,
        )
    }

    private fun decodeModuleGlobalEntries(entries: Map<String, ByteArray>): ModuleGlobalEntries {
        val prefs = entries["module/shared_prefs.json"]?.let { bytes ->
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            buildMap {
                val iterator = json.keys()
                while (iterator.hasNext()) {
                    val prefsName = iterator.next()
                    if (prefsName in MODULE_BACKUP_PREFS) {
                        put(prefsName, SharedPrefsSnapshot.fromJson(json.optJSONObject(prefsName)))
                    }
                }
            }
        }.orEmpty()
        val filePrefix = "module/files/"
        val files = entries.entries
            .asSequence()
            .filter { (path, _) -> path.startsWith(filePrefix) }
            .mapNotNull { (path, bytes) ->
                val relativePath = normalizeModuleBackupPath(path.removePrefix(filePrefix)) ?: return@mapNotNull null
                relativePath to bytes
            }
            .filter { (relativePath, _) -> isAllowedModuleBackupFile(relativePath) }
            .toMap()
        return ModuleGlobalEntries(prefs = prefs, files = files)
    }

    private fun decodeJsonArray(bytes: ByteArray?): List<JSONObject> {
        if (bytes == null) return emptyList()
        val array = JSONArray(String(bytes, Charsets.UTF_8))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(item)
            }
        }
    }

    private fun restoreAccountDataBundle(activity: Activity, bundle: DecodedAccountDataBundle) {
        restoreHostDatabaseEntries(activity, bundle)
        restoreUserFiles(activity, bundle)
        restoreModuleGlobalEntries(activity, bundle.module)
    }

    private fun restoreHostDatabaseEntries(activity: Activity, bundle: DecodedAccountDataBundle) {
        val databasePath = activity.applicationContext.getDatabasePath(HOST_DB_NAME)
        if (!databasePath.exists()) error("鏈壘鍒伴槄寰暟鎹簱")
        val database = SQLiteDatabase.openDatabase(
            databasePath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.beginTransaction()
            val accountId = bundle.account.accountId
            val oldBookIds = queryRows(
                database = database,
                sql = "SELECT id FROM books WHERE uid = ?",
                selectionArgs = arrayOf(accountId.toString()),
            ).mapNotNull { (it.opt("id") as? Number)?.toLong() }
            database.delete("book_reading", "uid = ?", arrayOf(accountId.toString()))
            deleteRowsForIds(database, "book_item_ref", "book_id", oldBookIds)
            deleteRowsForIds(database, "book_chapter", "book_id", oldBookIds)
            database.delete("books", "uid = ?", arrayOf(accountId.toString()))
            database.delete("users", "id = ?", arrayOf(accountId.toString()))
            insertRows(database, "users", bundle.database.users)
            val bookIdMap = insertImportedBooks(database, accountId, bundle.database.books)
            insertImportedChildRows(database, "book_item_ref", bundle.database.bookItemRefs, bookIdMap)
            insertImportedChildRows(database, "book_chapter", bundle.database.bookChapters, bookIdMap)
            insertImportedReadingRows(database, accountId, bundle.database.bookReading, bookIdMap)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            database.close()
        }
    }

    private fun restoreUserFiles(activity: Activity, bundle: DecodedAccountDataBundle) {
        val root = File(activity.filesDir, bundle.account.accountId.toString())
        if (root.exists()) {
            root.deleteRecursively()
        }
        if (bundle.files.isEmpty()) return
        root.mkdirs()
        bundle.files.forEach { (relativePath, bytes) ->
            val target = File(root, relativePath)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                output.write(bytes)
            }
        }
    }

    private fun restoreModuleGlobalEntries(activity: Activity, module: ModuleGlobalEntries) {
        module.prefs.forEach { (prefsName, snapshot) ->
            if (prefsName in MODULE_BACKUP_PREFS) {
                restoreSharedPrefs(activity, prefsName, snapshot)
            }
        }
        if (module.files.isEmpty()) return
        val root = activity.filesDir.canonicalFile
        MODULE_BACKUP_FILES.forEach { fileName ->
            runCatching { File(root, fileName).delete() }
        }
        MODULE_BACKUP_DIRS.forEach { dirName ->
            runCatching { File(root, dirName).deleteRecursively() }
        }
        module.files.forEach { (relativePath, bytes) ->
            val target = File(root, relativePath).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) return@forEach
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                output.write(bytes)
            }
        }
    }

    private fun insertRows(database: SQLiteDatabase, table: String, rows: List<JSONObject>) {
        rows.forEach { row ->
            insertOrReplace(database, table, jsonToContentValues(row))
        }
    }

    private fun insertImportedBooks(
        database: SQLiteDatabase,
        accountId: Long,
        rows: List<JSONObject>,
    ): Map<Long, Long> {
        val idMap = linkedMapOf<Long, Long>()
        rows.forEach { row ->
            val oldId = (row.opt("id") as? Number)?.toLong() ?: 0L
            val restoredRow = copyJsonRow(
                row = row,
                overrides = mapOf("uid" to accountId),
                nullColumns = setOf("id"),
            )
            val newId = insertOrReplace(database, "books", jsonToContentValues(restoredRow))
            if (newId > 0L && oldId > 0L) {
                idMap[oldId] = newId
            }
        }
        return idMap
    }

    private fun insertImportedChildRows(
        database: SQLiteDatabase,
        table: String,
        rows: List<JSONObject>,
        bookIdMap: Map<Long, Long>,
    ) {
        rows.forEach { row ->
            val oldBookId = (row.opt("book_id") as? Number)?.toLong() ?: return@forEach
            val newBookId = bookIdMap[oldBookId] ?: return@forEach
            val restoredRow = copyJsonRow(
                row = row,
                overrides = mapOf("book_id" to newBookId),
                nullColumns = setOf("id"),
            )
            insertOrReplace(database, table, jsonToContentValues(restoredRow))
        }
    }

    private fun insertImportedReadingRows(
        database: SQLiteDatabase,
        accountId: Long,
        rows: List<JSONObject>,
        bookIdMap: Map<Long, Long>,
    ) {
        rows.forEach { row ->
            val oldBookId = (row.opt("book_id") as? Number)?.toLong() ?: return@forEach
            val newBookId = bookIdMap[oldBookId] ?: return@forEach
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

    private fun queryRowsForIds(
        database: SQLiteDatabase,
        table: String,
        idColumn: String,
        ids: List<Long>,
    ): List<JSONObject> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return queryRows(
            database = database,
            sql = "SELECT * FROM $table WHERE $idColumn IN ($placeholders) ORDER BY id",
            selectionArgs = ids.map(Long::toString).toTypedArray(),
        )
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
            XposedBridge.log("$LOG_PREFIX failed to parse stored accounts: ${it.stackTraceToString()}")
            emptyList()
        }
    }

    private fun saveStoredAccounts(activity: Activity, accounts: List<StoredAccount>) {
        val array = JSONArray()
        accounts
            .filter { it.accountId > 0L }
            .sortedByDescending { it.lastUsedAt }
            .forEach { array.put(it.toJson()) }
        storePrefs(activity).edit().putString(STORE_KEY_ACCOUNTS, array.toString()).commit()
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
        }
    }

/*
    private fun decodePortableCredentials(raw: String): List<StoredAccount> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) error("鍓创鏉夸腑娌℃湁璐﹀彿鍑瘉")
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
            error("璐﹀彿鍑瘉鏍煎紡涓嶆纭?)
        }
        val json = runCatching { JSONObject(jsonString) }
            .getOrElse { error("璐﹀彿鍑瘉鏍煎紡涓嶆纭?) }
        return when {
            json.optString("type") == "credential_bundle" || json.has("accounts") -> {
                val array = json.optJSONArray("accounts") ?: error("璐﹀彿鍑瘉鏍煎紡涓嶆纭?)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val credential = item.optString("credential").trim()
                        when {
                            credential.isNotBlank() -> addAll(decodePortableCredentials(credential))
                            item.has("session") || item.has("localLibraryPrefs") -> add(StoredAccount.fromJson(item))
                            item.has("token") && item.has("userData") -> add(PortableCredential.fromJson(item).toStoredAccount())
                        }
                    }
                }.distinctBy { it.accountId }
            }
            json.has("session") || json.has("localLibraryPrefs") -> listOf(StoredAccount.fromJson(json))
            else -> listOf(PortableCredential.fromJson(json).toStoredAccount())
        }
    }

*/
    private fun decodePortableCredentials(raw: String): List<StoredAccount> {
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
                            credential.isNotBlank() -> addAll(decodePortableCredentials(credential))
                            item.has("session") || item.has("localLibraryPrefs") -> add(StoredAccount.fromJson(item))
                            item.has("token") && item.has("userData") -> add(PortableCredential.fromJson(item).toStoredAccount())
                        }
                    }
                }.distinctBy { it.accountId }
            }
            json.has("session") || json.has("localLibraryPrefs") -> listOf(StoredAccount.fromJson(json))
            else -> listOf(PortableCredential.fromJson(json).toStoredAccount())
        }
    }

    private fun updatePreference(session: Any, prefKey: Any, value: Any) {
        val method = allMethods(session.javaClass).firstOrNull {
            it.name == "update" && it.parameterTypes.size == 3
        } ?: error("Session.update not found")
        invokeSuspendMethod(method, session, prefKey, value)
    }

    private fun firstFlowValue(flow: Any): Any? {
        val flowKt = Class.forName(FLOW_KT_CLASS, false, flow.javaClass.classLoader ?: classLoader)
        val method = flowKt.methods.firstOrNull {
            (it.name == "firstOrNull" || it.name == "first") && it.parameterTypes.size == 2
        } ?: error("FlowKt.first not found")
        return invokeSuspendMethod(method, null, flow)
    }

    private fun invokeSuspendMethod(method: Method, target: Any?, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val continuationClass = Class.forName(KOTLIN_CONTINUATION_CLASS, false, classLoader)
        val throwOnFailure = Class.forName(KOTLIN_RESULT_KT_CLASS, false, classLoader)
            .declaredMethods.first {
                it.name == "throwOnFailure" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> emptyCoroutineContext()
                "resumeWith" -> {
                    val result = proxyArgs?.getOrNull(0)
                    runCatching {
                        throwOnFailure.invoke(null, result)
                        value = result
                    }.onFailure {
                        error = if (it is InvocationTargetException) it.targetException ?: it else it
                    }
                    latch.countDown()
                    targetUnit()
                }
                "toString" -> "ReaMicroAccountContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        val invocationArgs = args.toMutableList().apply { add(continuation) }.toTypedArray()
        val returned = try {
            method.invoke(target, *invocationArgs)
        } catch (invokeError: InvocationTargetException) {
            throw invokeError.targetException ?: invokeError
        }
        if (returned !== coroutineSuspendedMarker()) return returned
        if (!latch.await(SUSPEND_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            error("等待阅微协程结果超时")
        }
        error?.let { throw it }
        return value
    }

    private fun upsertUserRow(activity: Activity, account: StoredAccount) {
        runCatching {
            upsertUserRowViaHostDao(activity, account)
            return
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX host user dao upsert failed, fallback to sqlite: ${it.stackTraceToString()}")
        }
        val databasePath = activity.applicationContext.getDatabasePath(HOST_DB_NAME)
        if (!databasePath.exists()) error("未找到阅微数据库")
        val database = SQLiteDatabase.openDatabase(
            databasePath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            val values = ContentValues().apply {
                put("id", account.accountId)
                put("token", account.token)
                put("data", account.userData)
                put("updated", System.currentTimeMillis())
            }
            insertOrReplace(database, "users", values)
        } finally {
            database.close()
        }
    }

    private fun upsertUserRowViaHostDao(activity: Activity, account: StoredAccount) {
        val repository = userRepository(activity) ?: error("UserRepository not found")
        val userDaoClass = Class.forName(HOST_USER_DAO_CLASS, false, classLoader)
        val userDao = repository.javaClass.declaredFields
            .firstOrNull { userDaoClass.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
            ?.get(repository)
            ?: error("UserDao not found")
        val upsertMethod = allMethods(userDao.javaClass).firstOrNull {
            it.name == "upsert" && it.parameterTypes.size == 2
        } ?: error("UserDao.upsert not found")
        invokeSuspendMethod(upsertMethod, userDao, createHostUser(account))
    }

    private fun updateCachedUser(account: StoredAccount) {
        runCatching {
            val user = createHostUser(account)
            val cached = staticObject(CACHED_CLASS, "INSTANCE") ?: return@runCatching
            val userDataClass = Class.forName("$CACHED_CLASS\$DataType\$UserData", false, classLoader)
            val userData = userDataClass.constructors.first { it.parameterTypes.size == 1 }.newInstance(user)
            cached.javaClass.methods.firstOrNull {
                it.name == "save" && it.parameterTypes.size == 1
            }?.invoke(cached, userData)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to update cached user: ${it.stackTraceToString()}")
        }
    }

    private fun createHostUser(account: StoredAccount): Any {
        val userClass = Class.forName(HOST_USER_CLASS, false, classLoader)
        return userClass.getConstructor(
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
        ).newInstance(
            account.accountId,
            account.token,
            account.userData,
            System.currentTimeMillis(),
        )
    }

    private fun userRepository(activity: Activity): Any? =
        runCatching { method0(activity, "getUserRepository") }.getOrNull()
            ?: koinGet(activity, USER_REPOSITORY_CLASS)

    private fun readPreference(preferences: Any, key: Any): Any? =
        allMethods(preferences.javaClass).firstOrNull {
            it.name == "get" && it.parameterTypes.size == 1
        }?.invoke(preferences, key)

    private fun key(className: String, getterName: String): Any {
        val companion = staticObject(className, "INSTANCE")
            ?: error("Missing key companion $className")
        return method0(companion, getterName)
    }

    private fun staticObject(className: String, fieldName: String): Any? =
        runCatching {
            val field = Class.forName(className, false, classLoader).getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(null)
        }.getOrNull()

    private fun method0(target: Any, name: String): Any =
        allMethods(target.javaClass).first {
            it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
        }.apply { isAccessible = true }.invoke(target)

    private fun method1(target: Any, name: String, value: Any?) {
        allMethods(target.javaClass).firstOrNull {
            it.name == name && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }?.invoke(target, value)
    }

    private fun Any.longValue(name: String): Long =
        (method0(this, name) as? Number)?.toLong() ?: 0L

    private fun Any.stringValue(name: String): String =
        method0(this, name)?.toString().orEmpty()

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

    private data class DatabaseEntries(
        val users: List<JSONObject>,
        val books: List<JSONObject>,
        val bookItemRefs: List<JSONObject>,
        val bookChapters: List<JSONObject>,
        val bookReading: List<JSONObject>,
    )

    private data class ModuleGlobalEntries(
        val prefs: Map<String, SharedPrefsSnapshot> = emptyMap(),
        val files: Map<String, ByteArray> = emptyMap(),
    )

    private data class DecodedAccountDataBundle(
        val manifest: JSONObject,
        val account: StoredAccount,
        val database: DatabaseEntries,
        val files: Map<String, ByteArray>,
        val module: ModuleGlobalEntries,
    )

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
                session = if (hasFullState) session.mergeCredentialsFrom(other.session) else other.session,
                webDavPrefs = if (hasFullState) webDavPrefs.mergeKeysFrom(other.webDavPrefs, PORTABLE_WEBDAV_KEYS) else other.webDavPrefs,
                localLibraryPrefs = if (hasFullState) localLibraryPrefs else other.localLibraryPrefs,
                lastUsedAt = maxOf(lastUsedAt, other.lastUsedAt),
                hasFullState = hasFullState || other.hasFullState,
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
        val onlyNextPage: Boolean,
        val volumeKey: Boolean,
        val timeBattery: Boolean,
        val screenAlwaysOn: Boolean,
        val systemBars: Boolean,
        val agreement: Boolean,
        val baiduAuth: String,
        val aliyunAuth: String,
        val yun115Auth: String,
    ) {
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
                put("onlyNextPage", onlyNextPage)
                put("volumeKey", volumeKey)
                put("timeBattery", timeBattery)
                put("screenAlwaysOn", screenAlwaysOn)
                put("systemBars", systemBars)
                put("agreement", agreement)
                put("baiduAuth", baiduAuth)
                put("aliyunAuth", aliyunAuth)
                put("yun115Auth", yun115Auth)
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
                    onlyNextPage = source.optBoolean("onlyNextPage", false),
                    volumeKey = source.optBoolean("volumeKey", false),
                    timeBattery = source.optBoolean("timeBattery", false),
                    screenAlwaysOn = source.optBoolean("screenAlwaysOn", false),
                    systemBars = source.optBoolean("systemBars", false),
                    agreement = source.optBoolean("agreement", false),
                    baiduAuth = source.optString("baiduAuth"),
                    aliyunAuth = source.optString("aliyunAuth"),
                    yun115Auth = source.optString("yun115Auth"),
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

    private fun emptyCoroutineContext(): Any =
        Class.forName(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, false, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)

    private fun targetUnit(): Any =
        Class.forName(KOTLIN_UNIT_CLASS, false, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)

    private fun coroutineSuspendedMarker(): Any =
        runCatching {
            Class.forName(KOTLIN_INTRINSICS_CLASS, false, classLoader)
                .methods.first {
                    it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
                }.apply { isAccessible = true }
                .invoke(null)
        }.getOrElse {
            (Class.forName(KOTLIN_COROUTINE_SINGLETONS_CLASS, false, classLoader).enumConstants ?: emptyArray())
                .first { value -> value.toString() == "COROUTINE_SUSPENDED" }
        }

    private fun allMethods(type: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = type
        while (current != null) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
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
