# ⚡ GUÍA RÁPIDA - Ejecutar App SIN Storage (Referencia antigua)

Este documento es histórico y explica cómo ejecutar la app sin Storage si se usara Firebase Storage. El proyecto ahora usa Supabase Storage; si estás trabajando con Supabase, sigue las instrucciones en `SETUP.md` y en el dashboard de Supabase. Mantengo este archivo sólo como referencia si necesitas reproducir un entorno basado en Firebase.

### ✅ Lo que FUNCIONA:
- Registro y login completo
- Mensajes de texto en tiempo real
- Ver lista de conversaciones
- Crear salas de voz
- Ver participantes en salas
- Todo lo que NO involucre archivos

### ❌ Lo que NO funciona (mostrará alertas):
- Enviar imágenes en chats → "No se pueden enviar imágenes"
- Enviar videos en chats → "No se pueden enviar videos"
- Subir foto de perfil → "No se pueden cambiar fotos de perfil"
- Subir foto de portada → "No se pueden cambiar fotos de perfil"  
- Subir videos al feed → "No se pueden subir videos"

---

## 🚀 PASOS PARA EJECUTAR

### 1. Solo Firestore Rules (OBLIGATORIO)

Ve a Firebase Console → Firestore Database → Rules y pega:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function isSignedIn() {
      return request.auth != null;
    }
    
    function isOwner(userId) {
      return request.auth.uid == userId;
    }
    
    match /users/{userId} {
      allow read: if isSignedIn();
      allow create: if isSignedIn();
      allow update: if isOwner(userId);
      allow delete: if isOwner(userId);
    }
    
    match /messages/{messageId} {
      allow read: if isSignedIn();
      allow create: if isSignedIn();
      allow update: if isSignedIn();
      allow delete: if isOwner(resource.data.senderId);
    }
    
    match /conversations/{conversationId} {
      allow read: if isSignedIn();
      allow create: if isSignedIn();
      allow update: if isSignedIn();
    }
    
    match /videos/{videoId} {
      allow read: if true;
      allow create: if isSignedIn();
      allow update: if isSignedIn();
      allow delete: if isOwner(resource.data.uploaderId);
    }
    
    match /videoComments/{commentId} {
      allow read: if true;
      allow create: if isSignedIn();
      allow delete: if isOwner(resource.data.userId);
    }
    
    match /voiceRooms/{roomId} {
      allow read: if true;
      allow create: if isSignedIn();
      allow update: if isSignedIn();
      allow delete: if isOwner(resource.data.creatorId);
    }
  }
}
```

Click **Publicar**.

### 2. Compilar

En Android Studio:
```
Build > Clean Project
Build > Rebuild Project
```

### 3. Ejecutar

```
Run > Run 'app'
```

---

## 📱 Probar la App

### Test 1: Registro
1. Abre la app
2. Click "Registrarse"
3. Completa:
   - Nombre
   - Email
   - Contraseña
   - País (dropdown)
   - Ciudad (dropdown)
4. Click "Registrarse"
5. ✅ Deberías ver el HomeScreen

### Test 2: Mensajes
1. Click en tab "Mensajes"
2. ✅ Verás lista vacía
3. En un chat (si tuvieras contactos):
   - ✅ Puedes escribir texto y enviar
   - ❌ Click en ícono de imagen → "Función no disponible"
   - ❌ Click en ícono de video → "Función no disponible"

### Test 3: Perfil
1. Click en tab "Perfil"
2. ✅ Verás tu perfil (sin foto)
3. Click "Editar Perfil"
4. ✅ Puedes cambiar nombre/bio/país/ciudad
5. ❌ Click en portada → "Función no disponible"
6. ❌ Click en foto perfil → "Función no disponible"

---

## 🔄 HABILITAR Storage Después

Cuando tengas Plan Blaze:

1. Abre `Constants.kt`
2. Cambia:
   ```kotlin
   const val STORAGE_ENABLED = false
   ```
   a:
   ```kotlin
   const val STORAGE_ENABLED = true
   ```
3. Aplica las reglas de Storage del archivo `FIREBASE_CONFIG.md`
4. Recompila la app
5. ✅ Todo funcionará con imágenes/videos

---

## ⚠️ Importante

- Los usuarios verán mensajes claros cuando intenten usar funciones deshabilitadas
- Los íconos están en gris cuando Storage no está disponible
- La app NO crasheará, solo mostrará alertas informativas
- El código ya está preparado para habilitar Storage cuando quieras

---

**¡La app está lista para probar con solo mensajes de texto!** 🎉
