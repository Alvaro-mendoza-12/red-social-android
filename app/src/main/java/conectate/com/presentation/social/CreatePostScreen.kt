package conectate.com.presentation.social

import conectate.com.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.presentation.messages.MessageMediaComposer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePostScreen(
    navController: NavController,
    viewModel: CreatePostViewModel = hiltViewModel()
) {
    val draftAccountId = viewModel.currentAccountId
    key(draftAccountId) {
    var captionDraft by androidx.compose.runtime.saveable.rememberSaveable(draftAccountId, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    val caption = captionDraft.text
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsState()
    LaunchedEffect(captionDraft) {
        viewModel.updateMentionQuery(captionDraft.text, captionDraft.selection.end)
    }
    var remixAllowed by rememberSaveable(draftAccountId) { mutableStateOf(false) }
    var remixAudience by rememberSaveable(draftAccountId) { mutableStateOf("public") }
    var isSensitive by rememberSaveable(draftAccountId) { mutableStateOf(false) }
    val selectedMediaUris = rememberSaveable(draftAccountId, saver = listSaver(
        save = { uris: androidx.compose.runtime.snapshots.SnapshotStateList<Uri> -> uris.map(Uri::toString) },
        restore = { saved -> mutableStateListOf<Uri>().apply { addAll(saved.map(Uri::parse)) } },
    )) { mutableStateListOf<Uri>() }
    var showMediaComposer by remember { mutableStateOf(false) }
    val isCreating by viewModel.isCreating.collectAsState()
    val error by viewModel.error.collectAsState()

    var showDiscardDialog by rememberSaveable(draftAccountId) { mutableStateOf(false) }
    val onBack: () -> Unit = {
        if (!isCreating) {
            if (caption.isNotBlank() || selectedMediaUris.isNotEmpty() || remixAllowed || isSensitive) showDiscardDialog = true
            else navController.popBackStack()
        }
    }
    BackHandler(onBack = onBack)
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.audit_discard_title)) },
            text = { Text(stringResource(R.string.audit_discard_post_message)) },
            confirmButton = { TextButton(onClick = { navController.popBackStack() }) { Text(stringResource(R.string.audit_discard)) } },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.audit_keep_editing)) } },
        )
    }

    if (showMediaComposer) {
        MessageMediaComposer(
            maxItems = (7 - selectedMediaUris.size).coerceAtLeast(1),
            title = stringResource(R.string.audit_prepare_post),
            confirmContentDescription = stringResource(R.string.audit_add_to_post),
            isSending = false,
            onDismiss = { showMediaComposer = false },
            onSend = { media, preparedCaption ->
                selectedMediaUris.addAll(
                    media.map { it.uri }
                        .take((7 - selectedMediaUris.size).coerceAtLeast(0))
                )
                if (caption.isBlank() && preparedCaption.isNotBlank()) {
                    captionDraft = androidx.compose.ui.text.input.TextFieldValue(preparedCaption, androidx.compose.ui.text.TextRange(preparedCaption.length))
                }
                showMediaComposer = false
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.uploadSuccess.collect {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_new_post)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.audit_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.createPost(
                                caption = caption,
                                mediaUris = selectedMediaUris,
                                remixAllowed = remixAllowed,
                                remixAudience = remixAudience,
                                isSensitive = isSensitive
                            )
                        },
                        enabled = !isCreating && (caption.isNotBlank() || selectedMediaUris.isNotEmpty())
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.audit_publish), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (error != null) {
                Text(
                    text = stringResource(R.string.audit_post_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            TextField(
                value = captionDraft,
                onValueChange = { captionDraft = it },
                visualTransformation = conectate.com.presentation.components.mentionInputTransformation,
                enabled = !isCreating,
                placeholder = { Text(stringResource(R.string.audit_post_prompt)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            if (mentionSuggestions.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(12.dp)) {
                    Column {
                        mentionSuggestions.forEach { user ->
                            val handle = conectate.com.data.model.profilePublicHandle(user)
                            ListItem(
                                headlineContent = { Text(user.display_name ?: user.full_name ?: handle, maxLines = 1) },
                                supportingContent = { Text("@$handle", maxLines = 1) },
                                leadingContent = { conectate.com.presentation.components.CircularProfileImage(openMomentOnClick = false, ownerId = user.id, imageUrl = user.avatar_url, fallbackName = user.display_name) },
                                modifier = Modifier.clickable(enabled = !isCreating) {
                                    conectate.com.data.model.activeMentionAt(captionDraft.text, captionDraft.selection.end)?.let { active ->
                                        val (updated, cursor) = conectate.com.data.model.insertMention(captionDraft.text, active, handle)
                                        captionDraft = androidx.compose.ui.text.input.TextFieldValue(updated, androidx.compose.ui.text.TextRange(cursor))
                                    }
                                },
                            )
                        }
                    }
                }
            } else if (caption.isBlank()) {
                Text(stringResource(R.string.audit_mention_hint), style = MaterialTheme.typography.bodySmall)
            }

            if (selectedMediaUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedMediaUris) { uri ->
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { selectedMediaUris.remove(uri) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.audit_remove_attachment),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.audit_allow_remixes), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.audit_remix_credit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = remixAllowed,
                            onCheckedChange = { remixAllowed = it }
                        )
                    }
                    if (remixAllowed) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = remixAudience == "public",
                                onClick = { remixAudience = "public" },
                                label = { Text(stringResource(R.string.audit_everyone)) }
                            )
                            FilterChip(
                                selected = remixAudience == "followers",
                                onClick = { remixAudience = "followers" },
                                label = { Text(stringResource(R.string.audit_followers_only)) }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.audit_sensitive_content), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.audit_sensitive_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isSensitive, onCheckedChange = { isSensitive = it })
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    enabled = selectedMediaUris.size < 7,
                    onClick = {
                        showMediaComposer = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.audit_add_media))
                }
                Text(stringResource(R.string.audit_add_media), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    }
}
