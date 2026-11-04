package conectate.com.presentation.purpose

import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

@Composable
fun PostPurposeDialog(postId: String,navController: NavController,onDismiss: () -> Unit) {
    if(!purposeUuid(postId)) {
        AlertDialog(onDismissRequest = onDismiss,title = { Text("Seguimiento no disponible") },text = { Text("Esta publicación todavía no admite seguimiento. Sus comentarios siguen disponibles.") },confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
        return
    }
    PurposeExperienceDialog(PurposeTarget("social",postId),onDismiss,onSource = { source ->
        when(source.kind) {
            "comment" -> navController.navigate(Screen.PostDetail.createRoute(checkNotNull(source.parentId),source.id)) { launchSingleTop = true }
            "post" -> navController.navigate(Screen.PostDetail.createRoute(source.id)) { launchSingleTop = true }
            else -> conectate.com.presentation.common.AppFeedbackBus.show("Ese contenido ya no está disponible.")
        }
    })
}
