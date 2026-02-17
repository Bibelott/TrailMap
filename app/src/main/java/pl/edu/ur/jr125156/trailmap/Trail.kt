package pl.edu.ur.jr125156.trailmap

import com.mapbox.geojson.Point

data class Trail(
    val id: String,
    val name: String,
    val description: String,
    // A simplified list of points for the demo. 
    // In a real app, you'd load this from a GeoJSON file.
    val points: List<Point>
)

fun getSampleTrails(): List<Trail> {
    return listOf(
        Trail(
            id = "trail_1",
            name = "Peak Summit Loop",
            description = "A challenging 5km hike with great views.",
            points = listOf(
                // Replace these with coordinates near your mock location
                Point.fromLngLat(21.998, 50.0388),
                Point.fromLngLat(22.000, 50.040),
                Point.fromLngLat(22.005, 50.035)
            )
        )
    )
}