package conectate.com.presentation.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.User
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun profileBirthdayLabel(value: String?): String? = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es")))
}.getOrNull()

@Composable
internal fun ProfileIntroduction(user: User, isOwnProfile: Boolean, onEdit: () -> Unit, onBirthday: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Presentación", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (isOwnProfile) TextButton(onClick = onEdit) { Text("Editar") }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(user.bio?.takeIf(String::isNotBlank) ?: if (isOwnProfile) "Cuenta algo sobre ti en Editar perfil." else "Todavía no ha añadido una biografía.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                ProfileIntroductionRow(Icons.Default.School, "Estudios", user.education)
                ProfileIntroductionRow(Icons.Default.Work, "Profesión o carrera", user.career)
                ProfileIntroductionRow(Icons.Default.Business, "Trabaja en", user.workplace)
                ProfileIntroductionRow(Icons.Default.LocationOn, "Vive en", listOfNotNull(user.city?.takeIf(String::isNotBlank), user.country?.takeIf(String::isNotBlank)).joinToString(", "))
            }
        }
        ProfileDetailsSections(user)
        if (user.social_links.isNotEmpty()) {
            Text("Redes sociales", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ProfileSocialLinks(user.social_links)
        }
        // Birthday is intentionally the last presentation section. The API also removes hidden dates.
        val visible = isOwnProfile || user.profile_section_visibility["birthday"] != "only_me"
        val birthday = if (visible) profileBirthdayLabel(user.birth_date) else null
        if (birthday != null) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileIntroductionRow(Icons.Default.Cake, "Cumpleaños", birthday)
                    if (isOwnProfile && user.profile_section_visibility["birthday"] == "only_me")
                        Text("Solo tú puedes verlo", style = MaterialTheme.typography.labelMedium)
                    val isToday = runCatching { LocalDate.parse(user.birth_date).let { it.month == LocalDate.now().month && it.dayOfMonth == LocalDate.now().dayOfMonth } }.getOrDefault(false)
                    if (!isOwnProfile && isToday) Button(onClick = onBirthday) { Text("Felicítalo ahora") }
                }
            }
        }
    }
}

@Composable
private fun ProfileIntroductionRow(icon: ImageVector, title: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
