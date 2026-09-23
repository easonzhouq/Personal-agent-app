package com.example.agentchat.data.export

import android.content.ContentResolver
import android.net.Uri
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.ui.history.HistoryViewModel

/** Bridges CreateDocument callbacks to the export ViewModel. */
class ExportController(
    private val historyViewModel: HistoryViewModel,
    private val resolver: ContentResolver,
    private val secretStore: SecretStore,
    private val configIds: () -> Collection<String>,
) {
    private var pendingConversationId: String? = null

    fun requestDocument(conversationId: String) { pendingConversationId = conversationId }

    fun onCreateDocumentResult(destination: Uri?) {
        val conversationId = pendingConversationId
        pendingConversationId = null
        if (destination == null) historyViewModel.reportExportCancelled()
        else if (conversationId != null) historyViewModel.exportConversationWithSecrets(conversationId, resolver, destination, secretStore, configIds())
    }
}
