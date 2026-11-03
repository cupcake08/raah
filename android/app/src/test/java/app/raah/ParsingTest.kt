package app.raah

import app.raah.map.Mvt
import app.raah.map.RoadSource
import app.raah.mirror.MapsParser
import app.raah.proto.Maneuver
import app.raah.geo.LatLon
import app.raah.route.Photon
import app.raah.route.PlaceKind
import app.raah.route.SharedPlace
import app.raah.route.Place
import app.raah.route.Route
import app.raah.route.RouteTracker
import app.raah.route.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    @Test
    fun valhallaRoute() {
        val r = Fixtures.route
        assertEquals(
            listOf(Maneuver.STRAIGHT, Maneuver.RIGHT, Maneuver.LEFT, Maneuver.RIGHT, Maneuver.LEFT, Maneuver.RIGHT, Maneuver.ARRIVE),
            r.steps.map { it.maneuver },
        )
        assertEquals("100 Feet Road / SK Karim Khan Road", r.steps[2].street)
        assertEquals(1839.0, r.lengthM, 1.0)
        // Shape starts at the requested origin and every step points into it.
        assertEquals(12.9668, r.shape.first().lat, 0.0005)
        assertEquals(77.6409, r.shape.first().lon, 0.0005)
        assertTrue(r.steps.all { it.beginIdx in r.shape.indices })
    }

    @Test
    fun vectorTileRoads() {
        val layer = Mvt.lines(Fixtures.bytes("tile_14_11725_7596.pbf")!!, "transportation", setOf("class"))!!
        assertEquals(4096, layer.extent)
        val classes = layer.lines.groupingBy { it.props["class"] }.eachCount()
        assertTrue("got $classes", (classes["minor"] ?: 0) > 500 && (classes["primary"] ?: 0) > 10)
        // Every coordinate sits within the tile plus its buffer.
        assertTrue(layer.lines.all { l -> l.xy.all { it in -1024..5120 } })
    }

    @Test
    fun roadsAroundAPoint() {
        val roads = Fixtures.roads().roadsAround(Fixtures.route.shape[20], 1000.0)
        assertTrue(roads.size > 1000)
        assertTrue(roads.map { it.cls }.toSet().containsAll(listOf(0, 1, 2, 3)))
        assertEquals(11725 to 7596, RoadSource.tileOf(Fixtures.route.shape[20]))
    }

    @Test
    fun googleMapsNotification() {
        val s = MapsParser.parse("300 m", "Turn left onto 12th Main Road", "12 min · 3.4 km · 10:52 am ETA", 7)!!
        assertEquals(Maneuver.LEFT, s.maneuver)
        assertEquals(300L, s.distToTurnM)
        assertEquals("12th Main Road", s.primary)
        assertEquals(720L, s.timeLeftS)
        assertEquals(3400L, s.distLeftM)
        assertEquals(10 * 60 + 52, s.etaMinOfDay)

        val r = MapsParser.parse("Take the 2nd exit at the roundabout", "1.2 km", null, 0)!!
        assertEquals(Maneuver.ROUNDABOUT, r.maneuver)
        assertEquals(2, r.exitNum)
        assertEquals(1200L, r.distToTurnM)
    }

    @Test
    fun photonResults() {
        val json = """{"features":[{"geometry":{"coordinates":[77.6386624,12.9701976]},
            "properties":{"name":"Third Wave Coffee Roasters","housenumber":"1082","street":"12th Main Road","city":"Bengaluru"}}]}"""
        val p = Photon.parse(json).single()
        assertEquals("Third Wave Coffee Roasters", p.name)
        assertEquals("1082 12th Main Road, Bengaluru", p.detail)
        assertEquals(12.9701976, p.pos.lat, 1e-9)
    }

    @Test
    fun photonPrefersPlacesInRidingRange() {
        // "chinmaya" from Indiranagar: Photon's first hit was a namesake in Aluva, 400 km away.
        val aluva = Place("chinmaya", "Aluva", LatLon(10.1076, 76.3516))
        val far = Place("Chinmaya Mission Hospital", "Indiranagar", LatLon(12.9786, 77.6408))
        val near = Place("Chinmaya Vidyalaya", "Indiranagar", LatLon(12.9727, 77.6420))
        val ranked = Photon.rank(listOf(aluva, far, near), LatLon(12.9719, 77.6412))
        assertEquals(listOf(near, far, aluva), ranked)
        assertEquals(listOf(aluva, far, near), Photon.rank(listOf(aluva, far, near), null))
    }

    @Test
    fun photonKindsAndDuplicates() {
        val json = """{"features":[
            {"geometry":{"coordinates":[77.6452907,12.978657]},"properties":{"name":"Third Wave","osm_key":"amenity","osm_value":"cafe","district":"Indiranagar","city":"Bengaluru"}},
            {"geometry":{"coordinates":[77.6453,12.9787]},"properties":{"name":"Third Wave","osm_key":"building","osm_value":"yes","city":"Bengaluru"}},
            {"geometry":{"coordinates":[77.64,12.97]},"properties":{"name":"Indiranagar","osm_key":"place","osm_value":"suburb","city":"Bengaluru"}}]}"""
        val places = Photon.dedupe(Photon.parse(json))
        assertEquals(listOf("Third Wave", "Indiranagar"), places.map { it.name })
        assertEquals(PlaceKind.CAFE, places[0].kind)
        assertEquals("Indiranagar, Bengaluru", places[0].detail)
        assertEquals(PlaceKind.AREA, places[1].kind)
        assertEquals("Bengaluru", places[1].detail)
    }

    @Test
    fun coordinatesAsAPlace() {
        val p = Photon.coordinates("12.9719, 77.6412")!!
        assertEquals(12.9719, p.pos.lat, 1e-9)
        assertEquals(77.6412, p.pos.lon, 1e-9)
        assertEquals(null, Photon.coordinates("12th Main Road"))
        assertEquals(null, Photon.coordinates("100, 200"))
    }

    @Test
    fun sharedPlaces() {
        // Google Maps' Share: the name, then a short link that has to be followed.
        val g = SharedPlace.parse("Third Wave Coffee\nhttps://maps.app.goo.gl/AbCdEf123", null)
        assertEquals(SharedPlace.Link("https://maps.app.goo.gl/AbCdEf123", "Third Wave Coffee"), g)
        // Where that link leads: the place pin, not the map centre.
        val full = "https://www.google.com/maps/place/Third+Wave+Coffee/@12.9700,77.6300,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d12.9786570!4d77.6452907"
        val p = SharedPlace.mapsUrl(full, null)!!
        assertEquals("Third Wave Coffee", p.name)
        assertEquals(12.978657, p.pos.lat, 1e-6)
        assertEquals(77.6452907, p.pos.lon, 1e-6)

        val geo = SharedPlace.parse("geo:12.97,77.64?q=12.9786,77.6452(Third Wave)", null) as SharedPlace.At
        assertEquals("Third Wave", geo.place.name)
        assertEquals(SharedPlace.Query("Third Wave Coffee"), SharedPlace.parse("geo:0,0?q=Third+Wave+Coffee", null))
        assertEquals(SharedPlace.Query("Cubbon Park"), SharedPlace.parse("Cubbon Park", null))
        val pin = SharedPlace.parse("Meet here: 12.97190, 77.64120", null) as SharedPlace.At
        assertEquals(77.6412, pin.place.pos.lon, 1e-9)
    }

    @Test
    fun googleMapsLinkShapes() {
        // A searched-for spot.
        val s = SharedPlace.mapsUrl("https://www.google.com/maps/search/16.064556,+108.231402?entry=tts&g_ep=Eg", null)!!
        assertEquals(16.064556, s.pos.lat, 1e-9)
        assertEquals(108.231402, s.pos.lon, 1e-9)
        // A dropped pin: its "name" is its coordinates in degrees, which helps nobody.
        val dms = SharedPlace.mapsUrl("https://www.google.com/maps/place/22%C2%B025'28.4%22N+114%C2%B012'48.7%22E/@22.4245851,114.2134086,80m/data=!3m1!1e3!4m4!3m3!8m2!3d22.4245404!4d114.2135349", null)!!
        assertEquals("Dropped pin", dms.name)
        // An address without coordinates: search for it.
        val addr = "https://www.google.com/maps/place/Rev.+E.J.+Motau+st,+Kudube+Unit+2,+Temba/data=!4m2!3m1!1s0x1ebfc06ee282e2a9:0x843802deff256922!18m1!1e1"
        assertEquals(null, SharedPlace.mapsUrl(addr, null))
        assertEquals(SharedPlace.Query("Rev. E.J. Motau st, Kudube Unit 2, Temba"), SharedPlace.named(addr, "Somewhere"))
        assertEquals(SharedPlace.Query("Somewhere"), SharedPlace.named("https://maps.app.goo.gl/AbCdEf123", "Somewhere"))
        // Google Maps puts the floor before the shop's name.
        assertEquals("Starbucks - Sector 49" to "Ground Floor, Vatika Business Park, Sohna Rd",
            SharedPlace.nameAndAddress("Ground Floor, Starbucks - Sector 49, Vatika Business Park, Sohna Rd"))
        assertEquals("Fun City - Ambience Mall" to "Gurgaon", SharedPlace.nameAndAddress("Fun City - Ambience Mall, Gurgaon"))
        // A shared route: the destination by name, at its own coordinates rather than the map's centre.
        val shared = SharedPlace.parse("Shared route\nFrom My location to Ambience Mall via NH 48.\nhttps://maps.app.goo.gl/XyZ12345", null)
        assertEquals(SharedPlace.Link("https://maps.app.goo.gl/XyZ12345", "Ambience Mall"), shared)
        val dir = SharedPlace.mapsUrl("https://www.google.com/maps/dir/28.39,76.97/Ambience+Mall,+Gurugram/@28.45,77.03,12z/data=!3m1!4b1!4m9!4m8!1m1!4e1!1m5!1m1!1s0x0:0x0!2m2!1d77.09696!2d28.50487", "Ambience Mall")!!
        assertEquals("Ambience Mall", dir.name)
        assertEquals(28.50487, dir.pos.lat, 1e-9)
        assertEquals(77.09696, dir.pos.lon, 1e-9)
    }

    @Test
    fun googleFirstThenOsm() {
        val near = LatLon(12.9719, 77.6412)
        val google = listOf(Place("Toit Brewpub", "100 Feet Rd, Indiranagar", LatLon(12.97915, 77.64080)),
            Place("Toit", "Lisbon", LatLon(38.72, -9.14)))
        val osm = listOf(Place("Toit", "Indiranagar", LatLon(12.97920, 77.64085), PlaceKind.FOOD),
            Place("Toit ORR East", "Bellandur", LatLon(12.93, 77.69), PlaceKind.FOOD))
        val merged = Photon.merge(google, osm, near)
        // Google's Toit and OSM's are one place: Google's name and address, OSM's icon. The one in Lisbon only
        // shows when nothing nearby matches.
        assertEquals(listOf("Toit Brewpub", "Toit ORR East"), merged.map { it.name })
        assertEquals(PlaceKind.FOOD, merged[0].kind)
        assertEquals(listOf("Lisbon"), Photon.merge(google.drop(1), emptyList(), near).map { it.detail })
    }

    @Test
    fun photonNoise() {
        assertEquals(true, Photon.noise("railway", "subway", "Namma Metro - Reach 1"))
        assertEquals(true, Photon.noise("highway", "bus_stop", "KIA8: Electronic City => Kempegowda International Airport"))
        assertEquals(false, Photon.noise("railway", "station", "Indiranagar"))
    }

    @Test
    fun rideStartsWithTheFirstTurn() {
        // 11 points 100 m apart heading north; depart, turn right at the 5th, arrive at the end.
        val shape = (0..10).map { LatLon(28.40 + it * 0.0009, 76.97) }
        val steps = listOf(
            Step(Maneuver.STRAIGHT, 0, "Head north", "Sohna Road", 0, 500.0, 60.0),
            Step(Maneuver.RIGHT, 0, "Turn right", "Golf Course Road", 5, 500.0, 60.0),
            Step(Maneuver.ARRIVE, 0, "Arrive", "", 10, 0.0, 0.0),
        )
        val tracker = RouteTracker(Route(shape, steps, 1000.0, 120.0))
        // Waiting 15 m behind the start, as a ride often begins: the right turn is next, not the arrival.
        val p = tracker.update(LatLon(28.40 - 0.000135, 76.97), 20f)
        assertEquals(1, p.stepIdx)
        assertEquals(500.0, p.distToTurnM, 15.0)
        assertEquals(120.0, p.timeLeftS, 1.0)
    }
}
