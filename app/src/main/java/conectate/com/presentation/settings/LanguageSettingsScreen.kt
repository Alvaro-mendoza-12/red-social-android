package conectate.com.presentation.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import conectate.com.data.local.prefs.AppLocalePreferences

private data class AppLanguage(val tag: String, val name: String, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as? Activity
    val selected = remember(context) { AppLocalePreferences.selected(context) }
    val languages = remember {
        listOf(
            AppLanguage(AppLocalePreferences.SYSTEM, "Automático", "Usar el idioma del dispositivo"),
            AppLanguage("es", "Español", "Español"),
            AppLanguage("en", "English", "Inglés"),
            AppLanguage("pt", "Português", "Portugués")
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Idioma de la aplicación") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
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
                    "El cambio se aplica inmediatamente y también queda sincronizado con los ajustes de idioma de Android compatibles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(languages.size) { index ->
                val language = languages[index]
                ListItem(
                    headlineContent = { Text(language.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(language.detail) },
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
