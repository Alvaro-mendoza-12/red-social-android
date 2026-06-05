# Backup 20260507_1923 Supabase Restriction Runtime Hardening

Fecha: 2026-05-07 19:23 America/Bogota.

Motivo:
- Preparar una pasada de endurecimiento del login y cambio rapido de cuenta despues de validar en dispositivo real un bloqueo externo de Supabase por cuota (`exceed_storage_size_quota`).
- Conservar snapshot previo antes de cambiar el comportamiento de avisos persistentes y el gating de accesos rapidos.

Archivos respaldados:
- app/src/main/java/conectate/com/data/supabase/SupabaseServiceRestrictionSupport.kt
- app/src/main/java/conectate/com/presentation/auth/LoginScreen.kt
- app/src/main/java/conectate/com/presentation/profile/SettingsScreen.kt
- app/src/main/java/conectate/com/presentation/settings/AdvancedSettingsScreens.kt

Estado conocido al crear este backup:
- APK debug instalado y abierto en dispositivo `moto_g04` (`ZY22JSH8CF`).
- Smoke real: el QR de Conectate Web abre y rechaza QR invalidos correctamente.
- Hallazgo real: el acceso rapido con cuenta guardada llega al backend pero Supabase responde restriccion del proyecto por cuota de almacenamiento.
- Riesgo a corregir en esta pasada: el aviso puede desaparecer demasiado rapido y el acceso rapido puede seguir insinuando reintentos aunque el backend este restringido.
