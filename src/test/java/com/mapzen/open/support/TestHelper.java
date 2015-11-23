package com.mapzen.open.support;

import com.mapzen.pelias.gson.Feature;
import com.mapzen.pelias.gson.Geometry;
import com.mapzen.pelias.gson.Properties;
import com.mapzen.open.R;
import com.mapzen.open.activity.BaseActivity;
import com.mapzen.open.activity.InitialActivity;
import com.mapzen.pelias.SimpleFeature;
import com.mapzen.open.event.LocationUpdateEvent;
import com.mapzen.open.event.RoutePreviewEvent;
import com.mapzen.open.event.ViewUpdateEvent;
import com.mapzen.open.fragment.MapFragment;
import com.mapzen.open.login.LoginActivity;
import com.mapzen.open.util.DatabaseHelper;
import com.mapzen.osrm.Instruction;

import com.squareup.otto.Subscribe;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.robolectric.shadows.ShadowLocationManager;
import org.robolectric.tester.android.view.TestMenu;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.location.LocationManager;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static android.content.Context.LOCATION_SERVICE;
import static android.location.LocationManager.GPS_PROVIDER;
import static com.mapzen.pelias.SimpleFeature.ADMIN1;
import static com.mapzen.pelias.SimpleFeature.ADMIN1_ABBR;
import static com.mapzen.pelias.SimpleFeature.ID;
import static com.mapzen.pelias.SimpleFeature.LOCAL_ADMIN;
import static com.mapzen.pelias.SimpleFeature.TEXT;
import static com.mapzen.pelias.SimpleFeature.TYPE;
import static org.fest.assertions.api.Assertions.assertThat;
import static org.robolectric.Robolectric.application;
import static org.robolectric.Robolectric.buildActivity;
import static org.robolectric.Robolectric.shadowOf;

public final class TestHelper {

    public static final String MOCK_ROUTE_JSON = TestHelper.getFixture("basic_route");
    public static final String MOCK_NO_ROUTE_JSON = TestHelper.getFixture("no_route_found");
    public static final String MOCK_NY_TO_VT = TestHelper.getFixture("ny_to_vermont");
    public static final String MOCK_AROUND_THE_BLOCK = TestHelper.getFixture("around_the_block");
    public static final String MOCK_ACE_HOTEL = TestHelper.getFixture("ace_hotel");

    private TestHelper() {
    }

    public static void startFragment(Fragment fragment, FragmentActivity activity) {
        activity.getSupportFragmentManager().beginTransaction().add(fragment, null).commit();
    }

    public static TestBaseActivity initBaseActivity() {
        return initBaseActivityWithMenu(new TestMenu());
    }

    public static TestBaseActivity initBaseActivityWithMenu(TestMenu menu) {
        TestHelper.initLastKnownLocation();
        TestBaseActivity activity = buildActivity(TestBaseActivity.class)
                .create()
                .start()
                .resume()
                .visible()
                .get();

        activity.onCreateOptionsMenu(menu);
        return activity;
    }

    public static LoginActivity initLoginActivity() {
        LoginActivity activity = buildActivity(LoginActivity.class)
                .create()
                .start()
                .resume()
                .visible()
                .get();
        return activity;
    }

    public static InitialActivity initInitialActivity() {
        InitialActivity activity = buildActivity(InitialActivity.class)
                .create()
                .get();
        return activity;
    }

    public static MapFragment initMapFragment(BaseActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        MapFragment mapFragment = (MapFragment) manager.findFragmentById(R.id.map_fragment);
        mapFragment.setAct(activity);
        mapFragment.onStart();
        return mapFragment;
    }

    public static Location getTestLocation(double lat, double lng) {
        Location location = new Location("testing");
        location.setLatitude(lat);
        location.setLongitude(lng);
        return location;
    }

    public static Location getTestLocation(String provider, float lat, float lng, long time) {
        Location location = new Location(provider);
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setTime(time);
        return location;
    }

    public static Instruction getTestInstruction(double lat, double lng) {
        final Instruction instruction = getTestInstruction();
        instruction.setLocation(getTestLocation(lat, lng));
        return instruction;
    }

    public static Instruction getTestInstruction() {
        return getTestInstruction("19th Street", 160);
    }

    public static Instruction getTestInstruction(String name) {
        return getTestInstruction(name, 160);
    }

    public static Instruction getTestInstruction(int distanceInMeters) {
        return getTestInstruction("19th Street", distanceInMeters);
    }

    /**
     * For output format see <a href=https://github.com/Project-OSRM/osrm-backend/wiki/Output-json>
     * https://github.com/Project-OSRM/osrm-backend/wiki/Output-json</a>.
     */
    public static Instruction getTestInstruction(String name, int distanceInMeters) {
        final JSONArray jsonArray = new JSONArray();
        jsonArray.put(10)                    // instruction
                .put(name)                   // street name
                .put(distanceInMeters)       // distance in meters
                .put(0)                      // position
                .put(0)                      // time in seconds
                .put(distanceInMeters + "m") // length with unit
                .put("SE")                   // bearing
                .put(128);                   // azimuth
        return new Instruction(jsonArray);
    }

    public static Instruction getTestLastInstruction() {
        final JSONArray jsonArray = new JSONArray();
        jsonArray.put(15)                    // you have arrived
                .put("")                     // street name
                .put(0)                      // distance in meters
                .put(0)                      // position
                .put(0)                      // time in seconds
                .put("0m")                   // length with unit
                .put("N")                    // bearing
                .put(0);                     // azimuth
        return new Instruction(jsonArray);
    }

    public static SimpleFeature getTestSimpleFeature() {
        SimpleFeature simpleFeature = new SimpleFeature();
        simpleFeature.setLat(1.0);
        simpleFeature.setLon(1.0);
        simpleFeature.setProperty(TEXT, "Test SimpleFeature");
        simpleFeature.setProperty(ID, "123");
        simpleFeature.setProperty(TYPE, "geoname");
        simpleFeature.setProperty(ADMIN1, "New York");
        simpleFeature.setProperty(ADMIN1_ABBR, "NY");
        simpleFeature.setProperty(LOCAL_ADMIN, "Manhattan");
        simpleFeature.setHint("Test Hint");
        return simpleFeature;
    }

    public static Feature getTestFeature() {
        Feature feature = new Feature();
        Properties properties = new Properties();
        properties.setLabel("test");
        properties.setCountry("test");
        properties.setCountry_a("test");
        feature.setProperties(properties);
        Geometry geometry = new Geometry();
        List<Double> coordinates = new ArrayList<Double>();
        coordinates.add(0.0);
        coordinates.add(0.0);
        geometry.setCoordinates(coordinates);
        feature.setGeometry(geometry);
        return feature;
    }

    public static String getFixture(String name) {
        String basedir = System.getProperty("user.dir");
        File file = new File(basedir + "/src/test/fixtures/" + name + ".fixture");
        String fixture = "";
        try {
            fixture = FileUtils.readFileToString(file, "UTF-8");
        } catch (Exception e) {
            fixture = "not found";
        }
        return fixture;
    }

    public static void enableDebugMode(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor prefEditor = prefs.edit();
        prefEditor.putBoolean(context.getString(R.string.settings_key_debug), true);
        prefEditor.commit();
    }

    public static void initLastKnownLocation() {
        LocationManager locationManager = (LocationManager)
                application.getSystemService(LOCATION_SERVICE);
        ShadowLocationManager shadowLocationManager = shadowOf(locationManager);
        shadowLocationManager.setLastKnownLocation(GPS_PROVIDER, new Location(GPS_PROVIDER));
    }

    public static void populateDatabase(SQLiteDatabase db) throws Exception {
        populateRoutesTable(db);
        populateLocationsTable(db);
        populateRoutesGeometryTable(db);
        populateLogEntriesTable(db);
    }

    private static void populateLogEntriesTable(SQLiteDatabase db) {
        ContentValues logValues = new ContentValues();
        logValues.put(DatabaseHelper.COLUMN_TAG, "tag");
        logValues.put(DatabaseHelper.COLUMN_MSG, "log message");
        logValues.put(DatabaseHelper.COLUMN_TABLE_ID, UUID.randomUUID().toString());
        db.insert(DatabaseHelper.TABLE_LOG_ENTRIES, null, logValues);
    }

    private static void populateRoutesGeometryTable(SQLiteDatabase db) {
        String routeId = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COLUMN_TABLE_ID, UUID.randomUUID().toString());
        values.put(DatabaseHelper.COLUMN_ROUTE_ID, routeId);
        values.put(DatabaseHelper.COLUMN_POSITION, 0);
        values.put(DatabaseHelper.COLUMN_LAT, 0);
        values.put(DatabaseHelper.COLUMN_LNG, 0);
        db.insert(DatabaseHelper.TABLE_ROUTE_GEOMETRY, null, values);
    }

    public static void populateRoutesTable(SQLiteDatabase db) {
        ContentValues insertValues = new ContentValues();
        String routeId = UUID.randomUUID().toString();
        insertValues.put(DatabaseHelper.COLUMN_TABLE_ID, routeId);
        insertValues.put(DatabaseHelper.COLUMN_RAW, "blabla");
        db.insert(DatabaseHelper.TABLE_ROUTES, null, insertValues);
    }

    private static void populateLocationsTable(SQLiteDatabase db) throws Exception {
        db.insert(DatabaseHelper.TABLE_LOCATIONS, null,
                DatabaseHelper.valuesForLocationCorrection(TestHelper.getTestLocation(0.0, 0.0),
                        getTestLocation(1.0, 1.0), getTestInstruction(0.0, 0.0), "random-id"));
    }

    public static void assertSpan(Spanned spanned, ForegroundColorSpan foregroundColorSpan,
            int start, int end, int color) {
        assertThat(spanned.getSpanStart(foregroundColorSpan)).isEqualTo(start);
        assertThat(spanned.getSpanEnd(foregroundColorSpan)).isEqualTo(end);
        assertThat(foregroundColorSpan.getForegroundColor()).isEqualTo(color);
    }

    public static class TestLocation extends Location {

        public TestLocation(String provider) {
            super(provider);
        }

        private TestLocation(Builder builder) {
            super("fake tester");
            this.setSpeed(builder.speed);
            this.setBearing(builder.bearing);
            this.setLatitude(builder.originalLocation.getLatitude());
            this.setLongitude(builder.originalLocation.getLongitude());
        }

        public static class Builder {
            private float speed;
            private float bearing;
            private Location originalLocation;

            public Builder(Location location) {
                this.originalLocation = location;
            }

            public Builder setSpeed(float speed) {
                this.speed = speed;
                return this;
            }

            public Builder setBearing(float bearing) {
                this.bearing = bearing;
                return this;
            }

            public TestLocation build() {
                return new TestLocation(this);
            }
        }
    }

    public static class ViewUpdateSubscriber {
        private ViewUpdateEvent event;

        @Subscribe
        public void onViewUpdate(ViewUpdateEvent event) {
            this.event = event;
        }

        public ViewUpdateEvent getEvent() {
            return event;
        }
    }

    public static class LocationUpdateSubscriber {
        private LocationUpdateEvent event;

        @Subscribe
        public void onLocationUpdate(LocationUpdateEvent event) {
            this.event = event;
        }

        public LocationUpdateEvent getEvent() {
            return event;
        }
    }

    public static class RoutePreviewSubscriber {
        private RoutePreviewEvent event;

        @Subscribe
        public void onRoutePreviewEvent(RoutePreviewEvent event) {
            this.event = event;
        }

        public RoutePreviewEvent getEvent() {
            return event;
        }
    }
}
