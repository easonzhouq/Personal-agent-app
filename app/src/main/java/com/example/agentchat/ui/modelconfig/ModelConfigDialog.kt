package com.example.agentchat.ui.modelconfig

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.agentchat.data.provider.ProviderRegistry
import com.example.agentchat.domain.model.ModelConfig

@Composable
fun ModelConfigDialog(
    viewModel: ModelConfigViewModel,
    providerRegistry: ProviderRegistry,
    onUse: (ModelConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
        ) {
            ModelConfigScreen(
                viewModel = viewModel,
                providerRegistry = providerRegistry,
                onUse = onUse,
                onBack = onDismiss,
                inDialog = true,
            )
        }
    }
}
