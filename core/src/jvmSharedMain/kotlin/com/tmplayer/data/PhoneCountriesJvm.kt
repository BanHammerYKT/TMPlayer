package com.tmplayer.data

import dev.g000sha256.tdl.dto.CountryInfo

/** Telegram's list from tdl-coroutines, in the shape the picker wants. See [PhoneCountries.fromRows]. */
fun PhoneCountries.from(countries: List<CountryInfo>): List<Country> =
    fromRows(countries.map { CountryRow(it.countryCode, it.name, it.callingCodes.toList(), it.isHidden) })
