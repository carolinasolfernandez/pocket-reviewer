package dev.pocketreviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.pocketreviewer.R
import dev.pocketreviewer.config.Keys
import dev.pocketreviewer.config.SecretStore
import dev.pocketreviewer.data.KbParsing
import dev.pocketreviewer.data.KbRepoConfig
import dev.pocketreviewer.data.KbSource
import dev.pocketreviewer.data.KnowledgeBaseRepository
import dev.pocketreviewer.data.KnowledgeBaseRepository.KbFile
import dev.pocketreviewer.data.KnowledgeBaseRepository.KbInfo
import dev.pocketreviewer.data.KnowledgeBaseRepository.KbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Lets the user choose where the reviewer's rules come from: the built-in generic
 * rules, markdown files in a GitHub repository, or pasted text — plus optional extra
 * instructions that are appended to whichever source is selected.
 */
@Composable
fun KnowledgeBaseScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf<KbSettings?>(null) }
    var info by remember { mutableStateOf<KbInfo?>(null) }
    var files by remember { mutableStateOf<List<KbFile>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<KbFile?>(null) }

    suspend fun reloadFiles(source: KbSource) {
        files = withContext(Dispatchers.IO) { KnowledgeBaseRepository.files(context, source) }
    }

    LaunchedEffect(Unit) {
        val loaded = KnowledgeBaseRepository.settings(context)
        settings = loaded
        info = KnowledgeBaseRepository.info(context)
        reloadFiles(loaded.source)
    }

    val current = settings
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val saveSettings: (KbSettings) -> Unit = { updated ->
        settings = updated
        scope.launch {
            KnowledgeBaseRepository.saveSettings(context, updated)
            reloadFiles(updated.source)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.kb_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.kb_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Source picker ────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SourceOption(KbSource.BUILTIN, current.source, R.string.kb_source_builtin, R.string.kb_source_builtin_desc) {
                    saveSettings(current.copy(source = it))
                }
                SourceOption(KbSource.GITHUB, current.source, R.string.kb_source_github, R.string.kb_source_github_desc) {
                    saveSettings(current.copy(source = it))
                }
                SourceOption(KbSource.TEXT, current.source, R.string.kb_source_text, R.string.kb_source_text_desc) {
                    saveSettings(current.copy(source = it))
                }
            }
        }

        // ── Source-specific editor ───────────────────────────────────────────
        when (current.source) {
            KbSource.BUILTIN -> Unit
            KbSource.GITHUB -> GithubSourceEditor(
                config = current.repo,
                info = info,
                hasContent = files.isNotEmpty(),
                onSaveConfig = { saveSettings(current.copy(repo = it)) },
                onDownloaded = { newInfo ->
                    info = newInfo
                    scope.launch { reloadFiles(KbSource.GITHUB) }
                }
            )
            KbSource.TEXT -> TextSourceEditor(onSaved = { scope.launch { reloadFiles(KbSource.TEXT) } })
        }

        // ── Extra instructions (apply to every source) ───────────────────────
        ExtraInstructions(current.extraInstructions) { saveSettings(current.copy(extraInstructions = it)) }

        // ── What the reviewer will actually use ──────────────────────────────
        if (files.isNotEmpty()) {
            Text(
                stringResource(R.string.kb_files_in_use, files.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            files.forEach { f -> KbFileCard(f, onClick = { selectedFile = f }) }
        } else if (current.source != KbSource.BUILTIN) {
            Text(
                stringResource(R.string.kb_fallback_note),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF59E0B)
            )
        }
    }

    selectedFile?.let { f -> KbFileDialog(f) { selectedFile = null } }
}

@Composable
private fun SourceOption(
    option: KbSource, selected: KbSource, titleRes: Int, descRes: Int, onSelect: (KbSource) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(option) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = option == selected, onClick = { onSelect(option) })
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GithubSourceEditor(
    config: KbRepoConfig,
    info: KbInfo?,
    hasContent: Boolean,
    onSaveConfig: (KbRepoConfig) -> Unit,
    onDownloaded: (KbInfo) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var repo by remember(config) { mutableStateOf(config.repo) }
    var branch by remember(config) { mutableStateOf(config.branch) }
    var path by remember(config) { mutableStateOf(config.path) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val repoValid = KbParsing.parseRepo(repo) != null
    val updated = stringResource(R.string.kb_updated)
    val errorPrefix = stringResource(R.string.error_prefix)

    OutlinedTextField(
        value = repo, onValueChange = { repo = it },
        label = { Text(stringResource(R.string.kb_repo)) },
        placeholder = { Text("my-org/engineering-handbook") },
        isError = repo.isNotBlank() && !repoValid,
        supportingText = { Text(stringResource(R.string.kb_repo_help)) },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = branch, onValueChange = { branch = it },
            label = { Text(stringResource(R.string.kb_branch)) },
            placeholder = { Text(stringResource(R.string.kb_branch_placeholder)) },
            singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = path, onValueChange = { path = it },
            label = { Text(stringResource(R.string.kb_path)) },
            placeholder = { Text("docs/review-rules") },
            singleLine = true, modifier = Modifier.weight(1f)
        )
    }
    Text(
        stringResource(R.string.kb_path_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
        onClick = {
            val newConfig = KbRepoConfig(repo.trim(), branch.trim(), path.trim())
            onSaveConfig(newConfig)
            busy = true; message = null
            scope.launch {
                val result = runCatching {
                    KnowledgeBaseRepository.download(context, newConfig, SecretStore.get(context, Keys.GITHUB_TOKEN))
                }
                result.onSuccess { onDownloaded(it); isError = false; message = updated }
                    .onFailure { isError = true; message = "$errorPrefix ${it.message}" }
                busy = false
            }
        },
        enabled = repoValid && !busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(
            when {
                busy -> R.string.kb_downloading
                hasContent -> R.string.kb_update
                else -> R.string.kb_download
            }
        ))
    }

    message?.let {
        Text(
            it, style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else Color(0xFF34D399)
        )
    }
    if (info != null && hasContent) {
        Text(
            stringResource(
                R.string.kb_last_update,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info.lastUpdateMs)),
                info.fileCount, info.chars
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TextSourceEditor(onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stored by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        stored = withContext(Dispatchers.IO) { KnowledgeBaseRepository.customText(context) }
        text = stored
    }

    OutlinedTextField(
        value = text, onValueChange = { text = it },
        label = { Text(stringResource(R.string.kb_text_label)) },
        placeholder = { Text(stringResource(R.string.kb_text_placeholder)) },
        supportingText = { Text(stringResource(R.string.kb_text_help, text.length)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 420.dp)
    )
    Button(
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { KnowledgeBaseRepository.saveCustomText(context, text) }
                stored = text.trim()
                onSaved()
            }
        },
        enabled = text.trim() != stored,
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(if (text.trim() != stored) R.string.action_save else R.string.action_saved)) }
}

@Composable
private fun ExtraInstructions(stored: String, onSave: (String) -> Unit) {
    var text by remember(stored) { mutableStateOf(stored) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.kb_extra_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.kb_extra_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.kb_extra_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 240.dp)
            )
            Button(
                onClick = { onSave(text.trim()) },
                enabled = text.trim() != stored,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(if (text.trim() != stored) R.string.action_save else R.string.action_saved)) }
        }
    }
}

@Composable
private fun KbFileCard(file: KbFile, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KbFileDialog(file: KbFile, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(file.name.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            file.content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp, lineHeight = 16.sp,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}
