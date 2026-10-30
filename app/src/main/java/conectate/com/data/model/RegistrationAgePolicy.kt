package conectate.com.data.model

import java.time.LocalDate

const val MINIMUM_REGISTRATION_AGE = 13

fun isRegistrationBirthDateAllowed(birthDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    !birthDate.isAfter(today.minusYears(MINIMUM_REGISTRATION_AGE.toLong()))
