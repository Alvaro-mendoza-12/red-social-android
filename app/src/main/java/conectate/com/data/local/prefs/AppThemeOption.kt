package conectate.com.data.local.prefs

enum class AppThemeOption(
    val storageValue: String,
    val label: String
) {
    CONECTATE("conectate", "Conectate"),
    OCEANO("oceano", "Oceano"),
    JADE("jade", "Jade"),
    SOLAR("solar", "Solar"),
    SAKURA("sakura", "Sakura"),
    COBALTO("cobalto", "Cobalto"),
    BOSQUE("bosque", "Bosque"),
    AURORA("aurora", "Aurora"),
    GRAFITO("grafito", "Grafito lima"),
    RUBI("rubi", "Rubi menta"),
    CIRUELA("ciruela", "Ciruela oro"),
    ARTICO("artico", "Artico coral"),
    NEON("neon", "Neon urbano"),
    TERRACOTA("terracota", "Terracota cielo"),
    TINTA("tinta", "Tinta editorial"),
    LAGUNA("laguna", "Laguna coral"),
    CITRICO("citrico", "Citrico azul"),
    ORQUIDEA("orquidea", "Orquidea jade"),
    CARMESI("carmesi", "Carmesi hielo"),
    PRISMA("prisma", "Prisma vivo"),
    MELON("melon", "Melon oceano"),
    ACERO("acero", "Acero amapola"),
    SELVA("selva", "Selva electrica"),
    NOCHE("noche", "Noche lima");

    companion object {
        fun fromStorage(raw: String?): AppThemeOption {
            val normalized = raw?.trim()?.lowercase()
            return entries.firstOrNull { it.storageValue == normalized } ?: CONECTATE
        }
    }
}
