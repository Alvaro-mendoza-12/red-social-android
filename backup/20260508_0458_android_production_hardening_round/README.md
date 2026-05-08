# 2026-05-08 04:58 - Android production hardening round

## Objetivo de la ronda

Cerrar la deuda de compilación Android en `main`, sanear suites de test obsoletas, endurecer validaciones de media/autenticación y verificar que el APK vuelva a estar construible en debug y release.

## Resultado verificado

- `:app:compileDebugKotlin` OK
- `:app:testDebugUnitTest` OK
- `:app:lintDebug` OK
- `:app:qualityGateDebug` OK
- `:app:assembleDebug` OK
- `:app:assembleRelease` OK

## Notas honestas

- El backend compartido con Supabase sigue restringido por cuota, así que la validación funcional online completa sigue bloqueada por infraestructura externa.
- Persisten warnings Kotlin/lint no fatales y varios repositorios grandes; el baseline quedó mucho mejor, pero todavía no es una base "sin deuda" en sentido absoluto.
