package dev.nucleusframework.location

/**
 * One position fix.
 *
 * Optional values are `null` when the platform did not measure them — a Wi-Fi fix has no
 * altitude, a stationary device no bearing.
 *
 * @property latitude degrees, WGS 84, in `-90..90`
 * @property longitude degrees, WGS 84, in `-180..180`
 * @property altitude meters above the WGS 84 ellipsoid (sea level on iOS and macOS)
 * @property horizontalAccuracy radius of 68 % confidence around the coordinate, in meters
 * @property verticalAccuracy 68 % confidence of [altitude], in meters
 * @property bearing direction of travel, degrees clockwise from true north, in `0..360`
 * @property speed meters per second
 * @property timestampMillis when the fix was taken, milliseconds since the Unix epoch (UTC)
 * @property isCached whether the platform handed back a fix it had already acquired rather than
 *     measuring a new one; see [LocationProvider.currentLocation]'s `maxAge`
 */
public data class Location(
    public val latitude: Double,
    public val longitude: Double,
    public val altitude: Double? = null,
    public val horizontalAccuracy: Double? = null,
    public val verticalAccuracy: Double? = null,
    public val bearing: Double? = null,
    public val speed: Double? = null,
    public val timestampMillis: Long,
    public val isCached: Boolean = false,
)
