package conectate.com.presentation.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import conectate.com.data.local.prefs.AppLocalePreferences
import conectate.com.R

private data class AppLanguage(val tag: String, val name: String, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as? Activity
    val selected = remember(context) { AppLocalePreferences.selected(context) }
    val languages = remember {
        listOf(
            AppLanguage(AppLocalePreferences.SYSTEM, "", ""),
            AppLanguage("es", "Español", "Español"),
            AppLanguage("en", "English", "English"),
            AppLanguage("pt", "Português", "Português"),
            AppLanguage("fr", "Français", "Français"),
            AppLanguage("de", "Deutsch", "Deutsch"),
            AppLanguage("ja", "日本語", "日本語"),
            AppLanguage("zh-Hans", "简体中文", "简体中文"),
            AppLanguage("zh-Hant", "繁體中文", "繁體中文")
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.language_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.audit_language_coverage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(languages.size) { index ->
                val language = languages[index]
                ListItem(
                    headlineContent = { Text(if (language.tag == AppLocalePreferences.SYSTEM) stringResource(R.string.language_system) else language.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(if (language.tag == AppLocalePreferences.SYSTEM) stringResource(R.string.language_system_detail) else language.detail) },
                    trailingContent = {
                        RadioButton(
                            selected = selected == language.tag,
                            onClick = { activity?.let { AppLocalePreferences.apply(it, language.tag) } }
                        )
                    },
                    modifier = Modifier.clickable {
                        activity?.let { AppLocalePreferences.apply(it, language.tag) }
                    }
                )
            }
        }
    }
}
