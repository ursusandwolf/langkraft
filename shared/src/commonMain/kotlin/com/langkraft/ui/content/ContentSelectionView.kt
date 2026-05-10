package com.langkraft.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.domain.model.ImmersionContent

@Composable
fun ContentSelectionView(
    viewModel: ContentSelectionViewModel,
    onContentSelected: (ImmersionContent) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var urlInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Langkraft - Choose Content") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            // Import Section
            Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Import from YouTube", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("YouTube URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.importContent(urlInput) },
                        modifier = Modifier.align(Alignment.End),
                        enabled = !state.isImporting
                    ) {
                        if (state.isImporting) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Text("IMPORT")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Library Section
            Text("Your Library", style = MaterialTheme.typography.h5)
            Spacer(modifier = Modifier.height(16.dp))

            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.library) { content ->
                        LibraryItem(content, onClick = { onContentSelected(content) })
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryItem(content: ImmersionContent, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        elevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = content.title, style = MaterialTheme.typography.subtitle1)
                Text(
                    text = "${content.durationSeconds / 60}m ${content.durationSeconds % 60}s",
                    style = MaterialTheme.typography.caption
                )
            }
            Text("➔", style = MaterialTheme.typography.h6)
        }
    }
}
