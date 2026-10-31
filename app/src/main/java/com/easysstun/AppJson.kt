package com.easysstun

import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for app-owned data: the persisted profile store,
 * the profile payload passed to the VPN service, and the GitHub release
 * response.
 *
 * Unknown keys are ignored so a profile store written by a newer build (or
 * hand-edited) stays readable, and defaults are encoded so every field is
 * present when an older build reads the store back.
 */
internal val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
