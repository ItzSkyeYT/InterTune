/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * File format, Bitrate and Export to folder (Unreleased.DOWNLOAD_FORMATS), read from the source as
 * the other wiring tests are, since a flag is a constant of the build and MediaCodec cannot run
 * here: where the format is read, that a song is converted into a temporary file and put in the
 * folder only when whole (and saved as Original when the conversion fails), that the rows are
 * offered only with the flag, and that the texts are held with their French beside them. What the
 * formats and Auto's bitrate are is DownloadFormatsTest; the files are FileEncodersTest.
 */
class DownloadFormatsWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun fileCode(path: String) = code(File(sources, path).readText())

    private val util = fileCode("playback/DownloadUtil.kt")
    private val songFiles = fileCode("playback/downloadManager/SongFiles.kt")
    private val converter = fileCode("playback/downloadManager/SongConverter.kt")

    // ---- the flag

    @Test
    fun `the flag is held back like the others of debug and preview builds`() {
        assertTrue(Regex("""^\s*val DOWNLOAD_FORMATS = BuildConfig\.PREVIEW_FEATURES\s*$""", RegexOption.MULTILINE).containsMatchIn(fileCode("constants/Unreleased.kt")))
    }

    @Test
    fun `without the flag the stored setting is not read, and only the download code, the menus, the rows, the index and the report read the flag`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { Regex("""Unreleased\.DOWNLOAD_FORMATS\b""").containsMatchIn(code(it.readText())) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf(
                "playback/DownloadUtil.kt",
                "ui/menu/SelectionSongsMenu.kt",
                "ui/menu/SongMenu.kt",
                "ui/screens/settings/fragments/DownloadsFrag.kt",
                "ui/screens/settings/SettingsIndex.kt",
                "utils/ReportSettings.kt",
            ),
            readers,
        )
        // Both settings are read in the download code, once each, through the flag's rule.
        assertTrue("private val downloadFormat by enumPreference(context, DownloadFormatKey, DownloadFormat.ORIGINAL)" in util)
        assertTrue("private val downloadBitrate by enumPreference(context, DownloadBitrateKey, DownloadBitrate.AUTO)" in util)
        assertEquals(2, Regex("""DownloadFormats\.effective\(downloadFormat, Unreleased\.DOWNLOAD_FORMATS\)""").findAll(util).count())
        // The declaration and those two reads: the stored format is read nowhere else.
        assertEquals(3, Regex("""\bdownloadFormat\b""").findAll(util).count())
    }

    @Test
    fun `the settings are stored by name under their own keys`() {
        val keys = fileCode("constants/PreferenceKeys.kt")
        assertTrue("val DownloadFormatKey = stringPreferencesKey(\"downloadFormat\")" in keys)
        assertTrue("val DownloadBitrateKey = stringPreferencesKey(\"downloadBitrate\")" in keys)
    }

    // ---- the conversion

    @Test
    fun `a song is converted when the format is not Original, off the main thread, below the music, and the move is stopped by a cancellation`() {
        val save = util.substringAfter("private suspend fun saveSongFile(id: String").substringBefore("private fun songTags(")
        assertTrue("withContext(Dispatchers.IO)" in save)
        assertTrue("if (format == DownloadFormat.ORIGINAL) null else SongConversion(format, downloadBitrate, converter, { job?.isActive == false }, onEncode)" in save)
        // Below the music, lowered by the converter on the thread that encodes: set here, before a suspension,
        // the encoding could go on on another thread at full priority and leave this one lowered in the pool.
        assertFalse("not around code that suspends", "setThreadPriority" in save)
        val encode = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/SongConverter.kt").readText()
            .substringAfter("fun convert(").substringBefore("private fun encoderFor(")
        assertTrue(encode.indexOf("setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)") in 0 until encode.indexOf("decoder.open("))
        assertTrue("the priority is given back", "android.os.Process.setThreadPriority(priority)" in encode.substringAfterLast("} finally {"))
        assertTrue("a cancelled coroutine stops the conversion between two pieces", "{ job?.isActive == false }" in save)
        assertTrue("a cancellation is not taken for a failure to save", "if (it is CancellationException) throw it" in save)
        assertTrue("a song that fell back is counted for the notice", "conversion?.failure != null) notice?.fellBack()" in save)
        assertTrue("whatever made it fall back is told to the conversion", "override fun failed(e: Exception)" in converter)
    }

    @Test
    fun `the notice is up while songs are converted and gone after, even when the move fails`() {
        val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("private suspend fun saveSongFile(")
        assertTrue("notice = ConvertNotice(context, format, toMigrate.size)" in migrate)
        assertTrue("it says Converting only for a song that is encoded", "saveSongFile(s.key, notice) { notice?.starting(index, title) }" in migrate)
        val finally = migrate.substringAfter("} finally {")
        assertTrue("notice?.finish()" in finally)
        assertTrue("a notice left up by a killed app is cleared at the start", "ConvertNotice.clearStale(context)" in util)
        assertTrue("stale temporary files are swept with each run", "converter.sweep()" in migrate)
    }

    @Test
    fun `the converter writes into a temporary file of the app's cache and nothing else, and deletes it when it fails`() {
        assertTrue("""File(context.filesDir, "convert")""" in util)
        assertTrue("the work folder is not in the cache, which Android empties", "cacheDir" !in util.substringAfter("private val converter by lazy").substringBefore("\n"))
        assertTrue("one left by an app that was ended goes at the start", "converter.sweep(olderThanMs = 0)" in util)
        assertTrue("""File.createTempFile("convert-", PART, workDir)""" in converter)
        assertTrue("const val PART = \".part\"" in converter)
        val failure = converter.substringAfter("} catch (e: Throwable) {").substringBefore("} finally {")
        assertTrue("target.delete()" in failure)
        assertTrue("throw e" in failure)
        // It hands out a file only when the encoder has finished it.
        val body = converter.substringAfter("fun convert(").substringBefore("private fun encoderFor")
        assertTrue(body.indexOf("made.finish()") in 0 until body.indexOf("Converted(target,"))
        // The encoders know a temporary file and no folder.
        for (name in listOf("Mp3FileEncoder", "AacFileEncoder", "FlacFileEncoder", "WavFileEncoder")) {
            val text = fileCode("playback/downloadManager/$name.kt")
            assertFalse("$name touches a folder", "DocumentFile" in text || "contentResolver" in text || "localMgr" in text)
            assertTrue("$name writes to its file", "FileOutputStream(file)" in text || "RandomAccessFile(file" in text)
        }
    }

    @Test
    fun `the folder is asked for a file only after the conversion is whole, and a failure at any step saves the song as Original`() {
        val whole = songFiles.substringAfter("internal fun <T> saveStream(").substringBefore("/** What [source] holds").substringBefore("internal fun probe(")
        val converting = whole.indexOf("convert.convert(source, stream, tags)")
        val toFolder = whole.indexOf("save(SongFileNames.of(tags, converted.extension)) { out -> converted.copyTo(out) }")
        val original = whole.indexOf("return save(SongFileNames.of(tags, stream.extension), original)")
        assertTrue(converting in 0 until toFolder)
        assertTrue(toFolder < original)
        assertTrue("the temporary file goes in every case", "converted.close()" in whole && "finally" in whole)
        assertTrue("a failed conversion is logged with its reason", "Could not convert the song, it is saved as" in whole)
        assertTrue("a stop is not a failure", "catch (e: CancellationException)" in whole)
        assertTrue("a folder that refuses the file is not the end of the song", "Could not put the converted song in the folder" in whole)
        // A download falls back, an export does not: it is not handed a format it did not ask for.
        assertTrue("fallBack: Boolean = true" in songFiles)
        assertTrue("if (failed && !fallBack) return null" in whole)
        // The real stream is read once, before any of it; saveStream itself is SongFilesConversionTest.
        assertTrue("return saveStream(source, stream, tags, convert, fallBack, save) { out -> write(source, stream, tags, out) }" in songFiles)
    }

    @Test
    fun `a song YouTube sent as AAC is never encoded to AAC again`() {
        assertTrue("format == DownloadFormat.M4A && source == SourceCodec.AAC -> ConvertPlan.AsIs" in fileCode("playback/downloadManager/DownloadFormats.kt"))
        assertTrue("if (plan !is ConvertPlan.Encode) return null" in converter)
    }

    // ---- MP3

    @Test
    fun `the MP3 encoder is jump3r's Java port, and the part of it that needs javax_sound is never used`() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        assertTrue("""jump3r = { group = "de.sciss", name = "jump3r", version = "1.0.5" }""" in catalog)
        assertTrue("implementation(libs.jump3r)" in File("build.gradle.kts").readText())
        val users = sources.walkTopDown().filter { it.extension == "kt" }
            .filter { "de.sciss.jump3r" in it.readText() }.map { it.relativeTo(sources).path }.toSet()
        assertEquals(setOf("playback/downloadManager/Mp3FileEncoder.kt"), users)
        val text = File(sources, "playback/downloadManager/Mp3FileEncoder.kt").readText()
        assertFalse("jump3r.lowlevel" in text || "jump3r.Main" in text)
        // The two packages it is built from, and no more.
        assertTrue(Regex("""import de\.sciss\.jump3r\.(mp3|mpg)\.""").findAll(text).count() >= 15)
        val rules = File("proguard-rules.pro").readText()
        assertTrue("-dontwarn javax.sound.**" in rules)
    }

    @Test
    fun `the licence of jump3r is allowed for that library alone`() {
        val build = File("build.gradle.kts").readText()
        assertTrue("""allowedLicensesMap.put("LGPL v2.1+", listOf("de.sciss:jump3r"))""" in build)
        assertFalse("not for every library under that name", Regex("""allowedLicenses\.addAll\([^)]*LGPL v2\.1\+""").containsMatchIn(build))
    }

    // ---- the rows

    @Test
    fun `File format is under the download folder, behind the flag, and Bitrate is inside it and shown for MP3 and M4A only`() {
        val frag = fileCode("ui/screens/settings/fragments/DownloadsFrag.kt")
        val folder = frag.indexOf("Tour.SETTING_DOWNLOAD_FOLDER")
        val start = frag.indexOf("if (Unreleased.DOWNLOAD_FORMATS) {")
        assertTrue("the rows are not behind the flag", start > 0)
        assertTrue("under the folder row", start > folder)
        assertTrue("before the sizes", start < frag.indexOf("R.string.dl_size_used_cache"))
        val rows = frag.substring(start).substringAfter("{").substringBefore("Text(\n        text = stringResource(R.string.dl_size_used_cache")
        assertTrue("rememberEnumPreference(DownloadFormatKey, DownloadFormat.ORIGINAL)" in rows)
        assertTrue("Modifier.settingRow(R.string.download_format)" in rows)
        assertTrue("MeaningLine(stringResource(R.string.download_format_description))" in rows)
        for (name in listOf("DownloadFormat.MP3 -> \"MP3\"", "DownloadFormat.M4A -> \"M4A (AAC)\"", "DownloadFormat.FLAC -> \"FLAC\"", "DownloadFormat.WAV -> \"WAV\"")) {
            assertTrue(name, name in rows)
        }
        assertTrue("DownloadFormat.ORIGINAL -> stringResource(R.string.download_format_original)" in rows)
        // Bitrate: inside the flag, after File format, for the formats that have one, Auto first.
        val bitrate = rows.indexOf("if (DownloadFormats.bitrateRowShown(fileFormat)) {")
        assertTrue(bitrate > rows.indexOf("Modifier.settingRow(R.string.download_format)"))
        val bitrateRow = rows.substring(bitrate)
        assertTrue("rememberEnumPreference(DownloadBitrateKey, DownloadBitrate.AUTO)" in bitrateRow)
        assertTrue("Modifier.settingRow(R.string.download_bitrate)" in bitrateRow)
        assertTrue("R.string.download_bitrate_auto" in bitrateRow && "R.string.download_bitrate_kbps" in bitrateRow)
        assertTrue("R.string.download_bitrate_description" in bitrateRow)
        assertTrue("the AAC note only under M4A", "if (fileFormat == DownloadFormat.M4A)" in bitrateRow && "R.string.download_bitrate_aac_kept" in bitrateRow)
        assertFalse("no list of its own that could leave a choice out", "values =" in rows)
        // They write the settings and nothing else: no file is touched when one changes.
        assertFalse("downloadUtil" in rows)
    }

    @Test
    fun `both rows have their place in the search of Settings, the Bitrate one lit under File format when it is not there`() {
        val lines = fileCode("ui/screens/settings/SettingsIndex.kt").lines()
        val format = lines.single { "SettingEntry(Tour.ROUTE_STORAGE, R.string.download_format," in it }
        assertTrue("Tour.ROUTE_STORAGE" in format && "there = Unreleased.DOWNLOAD_FORMATS" in format && "R.string.search_words_download_format" in format)
        val bitrate = lines.single { "SettingEntry(Tour.ROUTE_STORAGE, R.string.download_bitrate," in it }
        assertTrue("Tour.ROUTE_STORAGE" in bitrate && "there = Unreleased.DOWNLOAD_FORMATS" in bitrate)
        assertTrue("under = R.string.download_format" in bitrate)
    }

    @Test
    fun `a report about downloads says the format and the bitrate too, behind the flag`() {
        val report = fileCode("utils/ReportSettings.kt")
        assertTrue("""Choice(DownloadFormatKey, "file format", DownloadFormat.ORIGINAL.name)""" in report)
        assertTrue("""Choice(DownloadBitrateKey, "bitrate", DownloadBitrate.AUTO.name)""" in report)
        assertTrue("fileFormat.takeIf { Unreleased.DOWNLOAD_FORMATS }, bitrate.takeIf { Unreleased.DOWNLOAD_FORMATS }" in report)
    }


    // ---- Export to folder

    /** The export's part of DownloadUtil: from its state to the song file's tags, which are code, since the comments are gone. */
    private val export = util.substringAfter("sealed interface ExportState {").substringBefore("private fun songTags(song: Song)")

    @Test
    fun `the entry is in the songs' selection menu and a song's own menu, behind the flag`() {
        val selection = fileCode("ui/menu/SelectionSongsMenu.kt")
        assertTrue("if (Unreleased.DOWNLOAD_FORMATS && !allLocal) {" in selection)
        assertTrue("title = R.string.export_to_folder" in selection)
        assertTrue("ExportItem(it.id, it.title, it.isLocal)" in selection)
        assertTrue("the entry comes after Download", selection.indexOf("DownloadGridMenu(") < selection.indexOf("R.string.export_to_folder"))
        val song = fileCode("ui/menu/SongMenu.kt")
        assertTrue("if (Unreleased.DOWNLOAD_FORMATS && !song.song.isLocal && onPhone) {" in song)
        assertTrue("a song that is on the phone: downloaded or whole in the song cache", "downloadUtil.isDownloaded(song.id) || runCatching { playerConnection.service.playerCache.holdsWhole(song.id) }" in song)
        assertTrue("title = R.string.export_to_folder" in song)
    }

    @Test
    fun `the sheet starts from the setting's choices, writes none of them, and keeps working when it is left`() {
        val sheet = fileCode("ui/dialog/ExportSheet.kt")
        assertTrue("var format by remember { mutableStateOf(savedFormat) }" in sheet)
        assertTrue("var bitrate by remember { mutableStateOf(savedBitrate) }" in sheet)
        // The setting's own folder first, or one picked through the system.
        assertTrue("remember { uriListFromString(context.dataStore.get(DownloadPathKey, \"\")).firstOrNull() }" in sheet)
        assertTrue("ActivityResultContracts.OpenDocumentTree()" in sheet)
        // The row for the bitrate under the same rule as Settings.
        assertTrue("if (DownloadFormats.bitrateRowShown(format)) {" in sheet)
        assertTrue("downloadUtil.startExport(items, format, bitrate, it)" in sheet)
        assertTrue("onValueSelected = { format = it }" in sheet && "onValueSelected = { bitrate = it }" in sheet)
        assertFalse("the setting is not written from the sheet", "onFileFormatChange" in sheet || "DownloadFormatKey, " in sheet.replace("rememberEnumPreference(DownloadFormatKey, DownloadFormat.ORIGINAL)", ""))
        assertTrue("a result nobody is looking at is told in a toast", "downloadUtil.exportWatchers.incrementAndGet()" in sheet && "exportWatchers.decrementAndGet()" in sheet)
        assertTrue("a result of an earlier export is not shown", "downloadUtil.acknowledgeExport()" in sheet)
        assertTrue("the line and the names", "exportLine(context, result)" in sheet && "ExportSummary.named(result, reason)" in sheet)
        assertTrue("a stop is offered", "downloadUtil.cancelExport()" in sheet)
    }

    @Test
    fun `an export reads what is on the phone and fetches no song`() {
        assertTrue(export.isNotBlank())
        for (network in listOf("YTPlayerUtils", "DownloadRequest", "DownloadService", "enqueue", "resolver", "dataSourceFactory", "download(")) {
            assertFalse("the export touches $network", network in export)
        }
        // The places it looks, in the order ExportSources says.
        assertTrue("localMgr.isExists(item.id)" in export)
        assertTrue("downloadCache.holdsWhole(item.id)" in export && "playerCache.holdsWhole(item.id)" in export)
        assertTrue("if (source == ExportSources.Source.NOWHERE) return SkipReason.NOT_ON_PHONE" in export)
        assertTrue("if (item.isLocal) return SkipReason.LOCAL" in export)
    }

    @Test
    fun `an exported copy carries no link, is never saved as another format than asked, and is not a second copy in the download folder`() {
        assertTrue("songTags(song).copy(keepLink = false)" in export)
        assertTrue("SongFiles.save(files, tags, conversion, fallBack = false)" in export)
        assertTrue("if (inDownloads) return SkipReason.ALREADY_THERE" in export)
        assertTrue("localMgr.allDirs.any { FolderNesting.isSameOrInside(target.toString(), it.uri.toString()) }" in export)
        // Every file made in the chosen folder is made whole or not at all (writeInto deletes what it began).
        assertEquals(3, Regex("""\bwriteInto\(""").findAll(export).count())
        assertTrue("fun writeInto(" in fileCode("playback/downloadManager/ExportFiles.kt"))
        assertTrue("file.delete()" in fileCode("playback/downloadManager/ExportFiles.kt").substringAfter("fun writeInto("))
    }

    @Test
    fun `a file of the same name already in the folder is left alone and named in the result, before anything is converted or copied`() {
        val files = fileCode("playback/downloadManager/ExportFiles.kt")
        assertTrue("internal fun hasFile(dir: DocumentFile, name: String): Boolean" in files)
        assertTrue("it is looked up by name in the folder, and a folder that cannot say is no folder with the file in it", "runCatching { dir.findFile(name) }.getOrNull() != null" in files)
        // The formats by their extension, a copy by its name, the cache's by the name the save gives it.
        assertTrue("if (format != DownloadFormat.ORIGINAL && hasFile(dir, SongFileNames.of(tags, format.extension ?: return SkipReason.FAILED))) return SkipReason.ALREADY_THERE" in export)
        assertTrue("val target = SongFileNames.of(tags, extension.ifEmpty { \"bin\" })" in export)
        assertTrue("if (hasFile(dir, target)) return SkipReason.ALREADY_THERE" in export)
        assertTrue("if (hasFile(dir, name)) { there = true; null } else writeInto(context, dir, name, write)" in export)
        assertTrue("if (saved != null) null else if (there) SkipReason.ALREADY_THERE else SkipReason.FAILED" in export)
        // Before the work: no song is converted for a file that is there.
        assertTrue(export.indexOf("hasFile(dir, SongFileNames.of(tags, format.extension") < export.indexOf("is ConvertPlan.Encode"))
        assertTrue(export.indexOf("if (hasFile(dir, target))") < export.indexOf("val copy = writeInto("))
        // The name asked for is the name a converted file is given.
        assertTrue("Converted(target, plan.format.extension!!," in converter)
        // And it is said as the reason the folder's own file is: "skipped: already in the folder".
        assertFalse("no second reason, so no second text", "NAME_TAKEN" in fileCode("playback/downloadManager/ExportPlan.kt"))
    }

    @Test
    fun `an export is stopped by a flag between two songs and two pieces, and keeps what it has done`() {
        assertTrue("private val exportStop = AtomicBoolean(false)" in export)
        assertTrue("fun cancelExport() = exportStop.set(true)" in export)
        assertTrue("if (exportStop.get()) { stopped = true; break }" in export)
        assertTrue("exportOne(item, format, bitrate, dir, inDownloads) { exportStop.get() }" in export)
        assertTrue("catch (e: CancellationException) {" in export)
        assertTrue("ExportResult(exported, skipped, stopped)" in export)
        assertTrue("a folder nothing can be written to leaves every song out, and writes nothing", "if (dir == null || !dir.isDirectory || !dir.canWrite())" in export)
        assertFalse("the converter lowers its own thread, not the export around its suspensions", "setThreadPriority" in export)
        assertTrue("the notice goes when it is over", "notice?.finish()" in export)
    }

    // ---- the texts

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()

    private fun tag(xml: String, name: String) = Regex("""<string name="$name"[^>]*>""").find(xml)?.value
    private fun text(xml: String, name: String) = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(xml)?.groupValues?.get(1)

    private val exportTexts = listOf(
        "export_to_folder", "export_note", "export_folder", "export_folder_none", "export_action",
        "export_progress", "export_done_title", "export_stopped_title", "export_names_more",
    )
    private val exportPlurals = listOf("export_exported", "export_skipped_not_downloaded", "export_skipped_there", "export_skipped_local", "export_failed")

    private val held = listOf(
        "download_format", "download_format_original", "download_format_description", "download_bitrate", "download_bitrate_auto",
        "download_bitrate_kbps", "download_bitrate_description", "download_bitrate_aac_kept", "convert_notice_title", "convert_notice_text",
        "convert_fallback_title",
    )

    @Test
    fun `the texts are held from translators, with their French beside them`() {
        for (name in held) {
            assertNotNull("$name is not in the English file", tag(english, name))
            assertTrue("$name is not held (translatable=\"false\")", "translatable=\"false\"" in tag(english, name)!!)
            assertNotNull("$name has no French", text(french, name))
        }
        assertTrue(Regex("""<plurals name="convert_fallback_text" translatable="false">""").containsMatchIn(english))
        assertTrue(french.contains("""<plurals name="convert_fallback_text">"""))
        // The search words are open to translators, as every search word is.
        assertFalse("translatable=\"false\"" in tag(english, "search_words_download_format")!!)
        assertNotNull(text(french, "search_words_download_format"))
    }

    @Test
    fun `the export texts are held from translators, with their French beside them`() {
        for (name in exportTexts) {
            assertNotNull("$name is not in the English file", tag(english, name))
            assertTrue("$name is not held", "translatable=\"false\"" in tag(english, name)!!)
            assertNotNull("$name has no French", text(french, name))
        }
        for (name in exportPlurals) {
            assertTrue("$name is not held", Regex("""<plurals name="$name" translatable="false">""").containsMatchIn(english))
            assertTrue("$name has no French", french.contains("""<plurals name="$name">"""))
        }
        assertEquals("Export to folder", text(english, "export_to_folder"))
        assertEquals("Exporter vers un dossier", text(french, "export_to_folder"))
    }

    @Test
    fun `the line an export ends with is made of plurals that say what the maintainer asked for`() {
        fun plural(xml: String, name: String, quantity: String) =
            Regex("""<plurals name="$name"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
                .let { Regex("""<item quantity="$quantity">(.*?)</item>""").find(it)?.groupValues?.get(1) }
        assertEquals("%1\$d song exported", plural(english, "export_exported", "one"))
        assertEquals("%1\$d songs exported", plural(english, "export_exported", "other"))
        assertEquals("%1\$d skipped: not downloaded", plural(english, "export_skipped_not_downloaded", "one"))
        // French has its three forms, which the language needs.
        for (name in exportPlurals) for (quantity in listOf("one", "many", "other")) assertNotNull("$name $quantity", plural(french, name, quantity))
    }

    @Test
    fun `the export texts are short and plain, in both languages`() {
        for ((language, xml) in listOf("English" to english, "French" to french)) {
            for (name in exportTexts) {
                val text = text(xml, name)!!
                assertFalse("$language $name has a long dash", '—' in text || '–' in text)
                assertFalse("$language $name has an exclamation mark", '!' in text)
                assertTrue("$language $name runs to ${text.length} characters", text.length <= 80)
            }
            for (name in exportPlurals) {
                val items = Regex("""<plurals name="$name"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
                for (item in Regex("""<item[^>]*>(.*?)</item>""").findAll(items).map { it.groupValues[1] }) {
                    assertFalse("$language $name has a long dash", '—' in item || '–' in item)
                    assertTrue("$language $name runs to ${item.length} characters", item.length <= 80)
                }
            }
        }
    }

    @Test
    fun `the words of the rows are the ones agreed`() {
        assertEquals("File format", text(english, "download_format"))
        assertEquals("Format des fichiers", text(french, "download_format"))
        assertEquals("Bitrate", text(english, "download_bitrate"))
        assertEquals("Débit", text(french, "download_bitrate"))
        assertEquals("Original keeps the best quality. Other formats are converted on the phone after the download.", text(english, "download_format_description"))
        assertEquals("%1\$d kbps", text(english, "download_bitrate_kbps"))
        // The note says plainly what converting cannot do, in both languages.
        assertTrue("Converting cannot add quality YouTube did not send." in text(english, "download_bitrate_description")!!)
        assertTrue("n\\'ajoute aucune qualité que YouTube n\\'a pas envoyée" in text(french, "download_bitrate_description")!!)
    }

    @Test
    fun `its texts are short and plain, in both languages`() {
        for ((language, xml) in listOf("English" to english, "French" to french)) {
            for (name in held) {
                val text = text(xml, name)!!
                assertFalse("$language $name has a long dash", '—' in text || '–' in text)
                assertFalse("$language $name has an exclamation mark", '!' in text)
                assertTrue("$language $name runs to more than three sentences", Regex("""[.?](?=\s|$)""").findAll(text).count() <= 3)
                assertTrue("$language $name runs to ${text.length} characters", text.length <= 130)
            }
            assertFalse("$language search words have a long dash", '—' in text(xml, "search_words_download_format")!!)
        }
    }
}
