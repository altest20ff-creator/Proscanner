package com.scanlite.app

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.File

internal data class SavedDocument(
    val file: File
) {
    val name: String
        get() = file.name
}

internal object SavedDocumentStore {
    private const val DIRECTORY = "documents"
    private const val TAG = "ScanLite.Documents"

    fun list(contextFilesDir: File): List<SavedDocument> {
        val directory = File(contextFilesDir, DIRECTORY)
        return directory.listFiles { file -> file.extension.equals("pdf", true) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map(::SavedDocument)
    }

    fun copyFromUri(
        resolver: ContentResolver,
        uri: Uri,
        contextFilesDir: File,
        fileName: String
    ): SavedDocument? {
        return try {
            val directory = File(contextFilesDir, DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                error("Unable to create ${directory.absolutePath}")
            }
            val safeName = fileName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "ScanLite-document.pdf" }
                .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
            val destination = File(directory, safeName)
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read exported PDF")
            SavedDocument(destination)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to save document uri=$uri", error)
            null
        }
    }

    fun delete(document: SavedDocument): Boolean {
        return try {
            document.file.delete()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to delete document=${document.name}", error)
            false
        }
    }
}
