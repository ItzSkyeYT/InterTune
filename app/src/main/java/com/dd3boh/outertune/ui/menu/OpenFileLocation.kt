/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.os.StrictMode
import android.provider.DocumentsContract
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.utils.shareSheet
import java.io.File

/**
 * Open file location (#150): a local song's menu shows the folder the file is in, in a file manager.
 * Android has no "show this file" intent, and file managers answer different ones, so the folder is
 * asked for in four ways ([asks]) and every app that answers one is offered, once, in the system's
 * picker. The document is the picker's own intent and the other ways are given to it as alternate
 * intents: the system resolves each itself, lists an app once whatever it answers, and starts the
 * intent that app answered. The first two apps found are also named, as explicit initial intents: the
 * picker lists those first and keeps no more than two of them (MAX_INITIAL_INTENTS in Android's chooser,
 * which a picker built without this code showed on the emulator), and a picker that ignored alternate
 * intents would still have them. An app in both is listed once.
 *
 * With nobody to add, the document is asked for as it always was, and the system opens the only app
 * or lists the several. Every way of asking is also an `<intent>` of the manifest's `<queries>`,
 * because from Android 11 an app sees only the apps it names there (OpenFileLocationTest).
 */
object OpenFileLocation {
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private val internal = Regex("""^/(?:storage/emulated|sdcard|storage/self)/(?:\d+/)?(.*)$""")
    private val card = Regex("""^/storage/([^/]+)/(.*)$""")
    private val sdcard = Regex("""^/sdcard(?=/|$)""")

    /**
     * The extra Samsung's My Files starts in a folder from, an absolute path. Checked on 15 Nov 2026 in
     * a decompiled copy of My Files: LaunchPages.setPageInfo reads it with getStringExtra, and the
     * action is declared on MultiInstanceLaunchActivity. Apps that open My Files at a folder send the same pair.
     */
    private const val SAMSUNG_START_PATH = "samsung.myfiles.intent.extra.START_PATH"

    /** One way of asking an app to show a folder. The manifest's `<queries>` holds each of them. */
    internal data class Ask(val action: String, val scheme: String?, val host: String?, val mimeType: String?)

    /** The folder as a document of the storage provider: Files by Google, the system's file app and the apps built on them answer it. */
    internal val documentAsk = Ask(Intent.ACTION_VIEW, "content", AUTHORITY, DocumentsContract.Document.MIME_TYPE_DIR)

    /** Samsung's My Files, which is given the folder as a path in an extra. */
    internal val samsungAsk = Ask("samsung.myfiles.intent.action.LAUNCH_MY_FILES", null, null, null)

    /** The two types file managers such as ZArchiver have long answered for a folder, asked with the folder's file address. */
    internal val resourceFolderAsk = Ask(Intent.ACTION_VIEW, "file", null, "resource/folder")
    internal val inodeDirectoryAsk = Ask(Intent.ACTION_VIEW, "file", null, "inode/directory")

    /** All of them; after the document, the order the other apps come in. */
    internal val asks = listOf(documentAsk, samsungAsk, resourceFolderAsk, inodeDirectoryAsk)

    /** The picker keeps two initial intents and no more (MAX_INITIAL_INTENTS in Android's chooser). */
    internal const val NAMED = 2

    /** An app that answers one of the [asks], by the activity that does. */
    internal data class Finding(val pkg: String, val activity: String, val ask: Ask)

    internal sealed interface Plan {
        /** Nobody was found: the document is asked for anyway, as a phone that hides its apps may still open it. */
        data object None : Plan

        /** The apps that answer the document, and no others: asked for as always, the system opens the one or lists them. */
        data object Document : Plan

        /** One app, and it wants a way of its own (a path, a file address): asked for directly. */
        data class Single(val app: Finding) : Plan

        /**
         * Two apps or more, some of them wanting a way of their own: the system's picker, with [primary] as its intent and the
         * [alternates] given to it, all resolved by the system. The primary is the document, or, when no app answers that, the first
         * way an app was found by. [named] are the first of the apps found, which the picker is also given by name.
         */
        data class Picker(val primary: Ask, val alternates: List<Ask>, val named: List<Finding>) : Plan
    }

    /**
     * What to do with what was found. [documentApps] are the packages that answer the document, [found] the
     * apps that answer another way, in the order of [asks]. An app counts once whatever it answers,
     * by the first way, and InterTune itself never counts. The picker is given the ways its extra apps
     * were found by, in that order: an app that answers several is started by the first of them.
     */
    internal fun plan(documentApps: Collection<String>, found: List<Finding>, own: String): Plan {
        val documents = documentApps.toSet() - own
        val others = found.filter { it.pkg != own && it.pkg !in documents }.distinctBy { it.pkg }
        return when {
            documents.isEmpty() && others.isEmpty() -> Plan.None
            others.isEmpty() -> Plan.Document
            documents.isEmpty() && others.size == 1 -> Plan.Single(others.first())
            else -> {
                val ways = others.map { it.ask }.distinct()
                val named = others.take(NAMED)
                if (documents.isNotEmpty()) Plan.Picker(documentAsk, ways, named) else Plan.Picker(ways.first(), ways.drop(1), named)
            }
        }
    }

    /** The folder's document id ("primary:Music/Rock", "1234-ABCD:Music") for a file's path, or null when the path is not on shared storage. */
    fun folderDocumentId(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val (volume, inside) = when {
            internal.matches(path) -> "primary" to internal.find(path)!!.groupValues[1]
            card.matches(path) -> card.find(path)!!.let { it.groupValues[1] to it.groupValues[2] }
            else -> return null
        }
        val folder = inside.substringBeforeLast('/', missingDelimiterValue = "")
        return "$volume:$folder"
    }

    /** The folder as a path ("/storage/emulated/0/Music/Rock") for a file's path, or null when the path is not on shared storage. */
    fun folderPath(path: String?): String? {
        if (path == null || folderDocumentId(path) == null) return null
        return path.substringBeforeLast('/').replaceFirst(sdcard, "/storage/emulated/0")
    }

    fun open(context: Context, path: String?) {
        val shown = runCatching { show(context, path) }.isSuccess
        if (!shown) Toaster.show(context, R.string.open_file_location_failed)
    }

    private fun show(context: Context, path: String?) {
        val id = folderDocumentId(path) ?: error("not a folder of shared storage")
        val folder = folderPath(path) ?: error("not a folder of shared storage")
        val pm = context.packageManager
        val document = intentFor(documentAsk, id, folder)
        val documentApps = query(pm, document).mapNotNull { it.activityInfo?.packageName }
        val found = (asks - documentAsk).flatMap { ask -> findings(pm, ask, intentFor(ask, id, folder)) }
        when (val how = plan(documentApps, found, context.packageName)) {
            Plan.None, Plan.Document -> launch(context, document, fileUris = false)
            is Plan.Single -> launch(context, startedBy(how.app, id, folder), fileUris = true)
            is Plan.Picker -> {
                // The app's one chooser builder, which leaves InterTune out of the list (ShareSheetTest); the title is
                // what a title given to the system's chooser call becomes.
                val chooser = context.shareSheet(intentFor(how.primary, id, folder))
                    .putExtra(Intent.EXTRA_TITLE, context.getString(R.string.open_file_location))
                if (how.alternates.isNotEmpty()) {
                    chooser.putExtra(Intent.EXTRA_ALTERNATE_INTENTS, how.alternates.map { intentFor(it, id, folder) }.toTypedArray<Parcelable>())
                }
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, how.named.map { startedBy(it, id, folder) }.toTypedArray<Parcelable>())
                if (!context.isActivity()) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launch(context, chooser, fileUris = true)
            }
        }
    }

    /** The intent for one way of asking. A new task, so the file manager opens on its own as it did before. */
    private fun intentFor(ask: Ask, documentId: String, folder: String): Intent {
        val intent = Intent(ask.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return when (ask) {
            documentAsk -> intent.setDataAndType(DocumentsContract.buildDocumentUri(AUTHORITY, documentId), ask.mimeType)
            samsungAsk -> intent.putExtra(SAMSUNG_START_PATH, folder)
            else -> intent.setDataAndType(Uri.fromFile(File(folder)), ask.mimeType)
        }
    }

    /** The intent for one app that was found: its way of asking, to its own activity. */
    private fun startedBy(app: Finding, documentId: String, folder: String): Intent =
        intentFor(app.ask, documentId, folder).setClassName(app.pkg, app.activity)

    /** The apps that answer one way, as the system's picker and startActivity count them: an app with no default category does not. */
    private fun findings(pm: PackageManager, ask: Ask, intent: Intent): List<Finding> =
        query(pm, intent).mapNotNull {
            val info = it.activityInfo
            if (info == null || !info.exported) null else Finding(info.packageName, info.name, ask)
        }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }.getOrDefault(emptyList())

    private fun Context.isActivity(): Boolean = this is Activity || (this is ContextWrapper && baseContext?.isActivity() == true)

    /**
     * The one place an intent leaves. A file address in an intent ends an app that targets Android 7 or later
     * (FileUriExposedException, StrictMode's default), and the file managers that answer "resource/folder"
     * take nothing else, so for a launch that carries one StrictMode's death on file addresses is lifted
     * and the policy in force is put back after, whatever the launch does.
     */
    private fun launch(context: Context, intent: Intent, fileUris: Boolean) {
        val before = StrictMode.getVmPolicy()
        try {
            if (fileUris) StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            context.startActivity(intent)
        } finally {
            if (fileUris) StrictMode.setVmPolicy(before)
        }
    }
}
