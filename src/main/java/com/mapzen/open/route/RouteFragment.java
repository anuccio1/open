package com.mapzen.open.route;

import com.mapzen.android.lost.api.LocationServices;
import com.mapzen.helpers.DistanceFormatter;
import com.mapzen.helpers.ZoomController;
import com.mapzen.open.MapController;
import com.mapzen.open.R;
import com.mapzen.open.activity.BaseActivity;
import com.mapzen.open.util.SimpleFeatureHelper;
import com.mapzen.pelias.SimpleFeature;
import com.mapzen.open.event.LocationUpdateEvent;
import com.mapzen.open.fragment.BaseFragment;
import com.mapzen.open.util.DatabaseHelper;
import com.mapzen.open.util.DisplayHelper;
import com.mapzen.open.util.Logger;
import com.mapzen.open.util.MapzenNotificationCreator;
import com.mapzen.open.util.RouteLocationIndicator;
import com.mapzen.open.util.VoiceNavigationController;
import com.mapzen.open.widget.DebugView;
import com.mapzen.open.widget.DistanceView;
import com.mapzen.osrm.Instruction;
import com.mapzen.osrm.Route;
import com.mapzen.osrm.Router;

import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.splunk.mint.Mint;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import org.json.JSONObject;
import org.oscim.core.GeoPoint;
import org.oscim.core.MapPosition;
import org.oscim.event.Event;
import org.oscim.map.Map;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;

import javax.inject.Inject;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;
import static com.mapzen.helpers.ZoomController.DrivingSpeed;
import static com.mapzen.open.MapController.geoPointToPair;
import static com.mapzen.open.MapController.getMapController;
import static com.mapzen.open.MapController.locationToPair;
import static com.mapzen.open.core.MapzenLocation.Util.getDistancePointFromBearing;
import static com.mapzen.pelias.SimpleFeature.TEXT;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_GROUP_ID;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_LAT;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_LNG;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_MSG;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_POSITION;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_RAW;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_ROUTE_ID;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_SPEED;
import static com.mapzen.open.util.DatabaseHelper.COLUMN_TABLE_ID;
import static com.mapzen.open.util.DatabaseHelper.TABLE_GROUPS;
import static com.mapzen.open.util.DatabaseHelper.TABLE_LOCATIONS;
import static com.mapzen.open.util.DatabaseHelper.TABLE_ROUTES;
import static com.mapzen.open.util.DatabaseHelper.TABLE_ROUTE_GEOMETRY;
import static com.mapzen.open.util.DatabaseHelper.TABLE_ROUTE_GROUP;
import static com.mapzen.open.util.DatabaseHelper.valuesForLocationCorrection;
import static com.mapzen.open.util.MixpanelHelper.Event.ROUTING_START;

public class RouteFragment extends BaseFragment implements DirectionListFragment.DirectionListener,
        ViewPager.OnPageChangeListener, Router.Callback, RouteEngine.RouteListener {
    public static final String TAG = RouteFragment.class.getSimpleName();
    public static final float DEFAULT_ROUTING_TILT = 45.0f;
    public static final double MIN_CHANGE_FOR_SHOW_RESUME = .00000001;
    public static final String ROUTE_TAG = "route";

    public static final float SLIDING_PANEL_OFFSET_OPEN = 1f;
    public static final float SLIDING_PANEL_OFFSET_CLOSED = 0f;
    public static final float SLIDING_PANEL_OFFSET_MARGIN = 0.1f;

    @Inject ZoomController zoomController;
    @Inject Router router;
    @Inject RouteEngine routeEngine;
    @Inject MapController mapController;
    @Inject MixpanelAPI mixpanelAPI;
    @Inject SQLiteDatabase db;
    @Inject Bus bus;
    @Inject RouteLocationIndicatorFactory routeLocationIndicatorFactory;

    @InjectView(R.id.routes) ViewPager pager;
    @InjectView(R.id.resume_button) ImageButton resume;
    @InjectView(R.id.footer_wrapper) RelativeLayout footerWrapper;
    @InjectView(R.id.destination_distance) DistanceView distanceToDestination;

    private ArrayList<Instruction> instructions;
    private RouteAdapter adapter;
    private Route route;
    protected String groupId;
    private RouteLocationIndicator routeLocationIndicator;
    private SimpleFeature simpleFeature;
    private String routeId;
    private int pagerPositionWhenPaused = 0;
    private double currentXCor;
    private DrawPathTask activeTask = null;

    VoiceNavigationController voiceNavigationController;
    private MapzenNotificationCreator notificationCreator;

    private boolean isRouting = false;
    private boolean isPaging = true;

    private SharedPreferences prefs;
    private Resources res;
    private DebugView debugView;
    private SlidingUpPanelLayout slideLayout;
    private MapOnTouchListener mapOnTouchListener;
    private DirectionListFragment directionListFragment;
    private RouteFragment fragment;
    private boolean shouldRestoreDirectionList = false;

    public static RouteFragment newInstance(BaseActivity act, SimpleFeature simpleFeature) {
        final RouteFragment fragment = new RouteFragment();
        fragment.setAct(act);
        fragment.setMapFragment(act.getMapFragment());
        fragment.setSimpleFeature(simpleFeature);
        fragment.groupId = UUID.randomUUID().toString();
        fragment.inject();
        fragment.setRetainInstance(true);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.route_widget, container, false);
        ButterKnife.inject(this, rootView);
        fragment = this;
        adapter = new RouteAdapter(act, instructions, fragment);
        adapter.setDestinationName(simpleFeature.getProperty(TEXT));
        TextView destinationName = (TextView) rootView.findViewById(R.id.destination_name);
        destinationName.setText(getString(R.string.routing_to_text) + simpleFeature
                .getProperty(TEXT));
        if (route != null) {
            distanceToDestination.setDistance(route.getTotalDistance());
        }
        pager.setAdapter(adapter);
        pager.setOnPageChangeListener(this);
        adapter.notifyDataSetChanged();
        currentXCor = mapFragment.getMap().getMapPosition().getX();
        initSpeakerbox();
        initNotificationCreator();
        pager.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                turnAutoPageOff();
                return false;
            }
        });
        initDebugView(rootView);
        initSlideLayout(rootView);
        setMapOnTouchListener();

        res = act.getResources();
        prefs = getDefaultSharedPreferences(act);

        if (LocationServices.FusedLocationApi != null) {
            if (prefs.getBoolean(getString(R.string.settings_mock_gpx_key), false)) {
                final String key = getString(R.string.settings_mock_gpx_filename_key);
                final String defaultFile =
                        getString(R.string.settings_mock_gpx_filename_default_value);
                final String filename = prefs.getString(key, defaultFile);
                final File file = new File(Environment.getExternalStorageDirectory(),
                        filename);
                LocationServices.FusedLocationApi.setMockMode(true);
                LocationServices.FusedLocationApi.setMockTrace(file);
            } else {
                LocationServices.FusedLocationApi.setMockMode(false);
            }
        }

        hideLocateButtonAndAttribution();
        return rootView;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        hideDirectionListFragment();
        if (getSlideLayout().getPanelState() == SlidingUpPanelLayout.PanelState.EXPANDED) {
            shouldRestoreDirectionList = true;
        }
    }

    private void setMapOnTouchListener() {
        mapOnTouchListener = new MapOnTouchListener();
        act.findViewById(R.id.map).setOnTouchListener(mapOnTouchListener);
    }

    private void initNotificationCreator() {
        notificationCreator = new MapzenNotificationCreator(act);
        if (instructions != null) {
            notificationCreator.createNewNotification(SimpleFeatureHelper.getMarker(simpleFeature)
                    .title, instructions.get(0).getFullInstruction(getActivity()));
        }
    }

    private void initSpeakerbox() {
        voiceNavigationController = new VoiceNavigationController(getActivity());
    }

    @OnClick(R.id.resume_button)
    public void onClickResume() {
        resumeAutoPaging();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mixpanelAPI.track(ROUTING_START, null);
        createGroup();
        bus.register(this);
    }

    private void createGroup() {
        ContentValues groupValue = new ContentValues();
        groupValue.put(COLUMN_TABLE_ID, groupId);
        groupValue.put(COLUMN_MSG, getGPXDescription());
        insertIntoDb(TABLE_GROUPS, null, groupValue);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        this.act = (BaseActivity) activity;
    }

    @Override
    public void onResume() {
        super.onResume();
        routeLocationIndicatorFactory.setMap(act.getMap());
        setRouteLocationIndicator(routeLocationIndicatorFactory.getRouteLocationIndicator());
        if (route != null) {
            Location startPoint = route.getStartCoordinates();
            routeLocationIndicator.setPosition(startPoint.getLatitude(), startPoint.getLongitude());
            routeLocationIndicator.setRotation((float) route.getCurrentRotationBearing());
            mapFragment.getMap().layers().add(routeLocationIndicator);
            mapFragment.hideLocationMarker();
        }

        setupZoomController();
        act.disableActionbar();
        act.hideActionBar();
        app.deactivateMoveMapToLocation();
        setupLinedrawing();
        initMapPosition();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mapFragment != null) {
            mapFragment.showLocationMarker();
            if (mapFragment.getMap() != null && mapFragment.getMap().layers() != null) {
                mapFragment.getMap().layers().remove(routeLocationIndicator);
            }
        }
    }

    private void initMapPosition() {
        manageMap(route.getStartCoordinates(), route.getStartCoordinates());
        getMapController().getMap().viewport().setTilt(DEFAULT_ROUTING_TILT);
    }

    @Override
    public void onDestroy() {
        app.activateMoveMapToLocation();
        teardownLinedrawing();
        markReadyForUpload();
        mapController.clearLines();
        act.updateView();

        bus.unregister(this);
        showLocateButton();
        if (LocationServices.FusedLocationApi != null) {
            LocationServices.FusedLocationApi.setMockMode(false);
        }
        super.onDestroy();
    }

    public RouteLocationIndicator getRouteLocationIndicator() {
        return routeLocationIndicator;
    }

    public void setRouteLocationIndicator(RouteLocationIndicator routeLocationIndicator) {
        this.routeLocationIndicator = routeLocationIndicator;
    }

    public void createRouteTo(Location location) {
        mapController.clearLines();
        mapFragment.clearMarkers();
        mapFragment.updateMap();
        isRouting = true;
        act.showLoadingIndicator();
        router.clearLocations()
                .setLocation(locationToPair(location))
                // To allow routing to see which direction you are travelling
                .setLocation(locationToPair(getDistancePointFromBearing(location, 15,
                        (int) Math.floor(location.getBearing()))))
                .setLocation(geoPointToPair(SimpleFeatureHelper.getGeoPoint(simpleFeature)))
                .setCallback(this)
                .fetch();
    }

    public String getRouteId() {
        return routeId;
    }

    public int getAdvanceRadius() {
        return ZoomController.DEFAULT_TURN_RADIUS;
    }

    public void setInstructions(ArrayList<Instruction> instructions) {
        Logger.d("instructions: " + instructions);
        this.instructions = instructions;
    }

    public SimpleFeature getSimpleFeature() {
        return simpleFeature;
    }

    public void setSimpleFeature(SimpleFeature simpleFeature) {
        this.simpleFeature = simpleFeature;
    }

    public GeoPoint getDestinationPoint() {
        return SimpleFeatureHelper.getGeoPoint(simpleFeature);
    }

    private void manageMap(Location originalLocation, Location location) {
        if (location != null) {
            zoomController.setAverageSpeed(getAverageSpeed());
            zoomController.setCurrentSpeed(originalLocation.getSpeed());
            if (isPaging) {
                mapController.setZoomLevel(zoomController.getZoom());
                mapController.quarterOn(location, route.getCurrentRotationBearing());
            }
            routeLocationIndicator.setRotation((float) route.getCurrentRotationBearing());
            routeLocationIndicator.setPosition(location.getLatitude(), location.getLongitude());
            mapFragment.updateMap();
        }
    }

    private ZoomController setupZoomController() {
        initZoomLevel(DrivingSpeed.MPH_0_TO_15, R.string.settings_zoom_driving_0to15_key,
                R.integer.zoom_driving_0to15);
        initZoomLevel(DrivingSpeed.MPH_15_TO_25, R.string.settings_zoom_driving_15to25_key,
                R.integer.zoom_driving_15to25);
        initZoomLevel(DrivingSpeed.MPH_25_TO_35, R.string.settings_zoom_driving_25to35_key,
                R.integer.zoom_driving_25to35);
        initZoomLevel(DrivingSpeed.MPH_35_TO_50, R.string.settings_zoom_driving_35to50_key,
                R.integer.zoom_driving_35to50);
        initZoomLevel(DrivingSpeed.MPH_OVER_50, R.string.settings_zoom_driving_over50_key,
                R.integer.zoom_driving_over50);

        return zoomController;
    }

    private void initZoomLevel(DrivingSpeed speed, int key, int defKey) {
        zoomController.setDrivingZoom(prefs.getInt(getString(key), res.getInteger(defKey)), speed);
    }

    public void onLocationChanged(Location location) {
        if (isRouting) {
            return;
        }

        routeEngine.onLocationChanged(location);
    }

    @Override
    public void onRecalculate(Location location) {
        createRouteTo(location);
        voiceNavigationController.recalculating();
        displayRecalculatePagerView();
    }

    private void displayRecalculatePagerView() {
        final View view = getPagerViewForIndex(pager.getCurrentItem());
        if (view != null) {
            TextView fullBefore = (TextView) view.findViewById(R.id.full_instruction);
            fullBefore.setText(R.string.recalculating);

            TextView fullAfter = (TextView) view.findViewById(R.id.full_instruction_after_action);
            fullAfter.setText(R.string.recalculating);

            view.findViewById(R.id.left_arrow).setVisibility(View.GONE);
            view.findViewById(R.id.turn_container).setVisibility(View.GONE);
            view.findViewById(R.id.right_arrow).setVisibility(View.GONE);
        }
    }

    @Override
    public void onSnapLocation(Location originalLocation, Location snapLocation) {
        storeLocationInfo(originalLocation, snapLocation);
        manageMap(originalLocation, snapLocation);
        debugView.setCurrentLocation(originalLocation);
        debugView.setSnapLocation(snapLocation);
        debugView.setAverageSpeed(getAverageSpeed());
        logForDebugging(originalLocation, snapLocation);
    }

    @Override
    public void onApproachInstruction(int index) {
        if (index < instructions.size() - 1) {
            voiceNavigationController.playInstruction(instructions.get(index));
        }
    }

    @Override
    public void onInstructionComplete(int index) {
        if (isPaging) {
            voiceNavigationController.playFlippedInstruction(instructions.get(index));
            if (isLastInstructionBeforeDestination(index)) {
                flipInstruction(index);
            } else if (hasNextInstruction(index)) {
                showInstruction(index + 1);
            }
        } else {
            pagerPositionWhenPaused = index + 1;
        }
    }

    private boolean isLastInstructionBeforeDestination(int index) {
        return index == instructions.size() - 2;
    }

    private boolean hasNextInstruction(int index) {
        return index + 1 < instructions.size() && instructions.get(index) != null;
    }

    private void showInstruction(int index) {
        if (isPaging) {
            final Instruction instruction = instructions.get(index);
            pagerPositionWhenPaused = index;
            Logger.logToDatabase(act, db, ROUTE_TAG,
                    "paging to instruction: " + instruction.toString());
            pager.setCurrentItem(index);
            debugView.setClosestInstruction(instruction);
        } else {
            pagerPositionWhenPaused = index;
        }
    }

    private void flipInstruction(int index) {
        final View view = getPagerViewForIndex(index);
        if (view != null) {
            TextView fullBefore = (TextView) view.findViewById(R.id.full_instruction);
            TextView fullAfter =
                    (TextView) view.findViewById(R.id.full_instruction_after_action);
            fullBefore.setVisibility(View.GONE);
            fullAfter.setVisibility(View.VISIBLE);
            ImageView turnIconBefore = (ImageView) view.findViewById(R.id.turn_icon);
            turnIconBefore.setVisibility(View.GONE);
            ImageView turnIconAfter =
                    (ImageView) view.findViewById(R.id.turn_icon_after_action);
            turnIconAfter.setVisibility(View.VISIBLE);
            setCurrentPagerItemStyling(index);
        }
    }

    @Override
    public void onUpdateDistance(int distanceToNextInstruction, int distanceToDestination) {
        debugView.setClosestDistance(route.getCurrentInstruction().getLiveDistanceToNext());
        this.distanceToDestination.setDistance(distanceToDestination);
        this.distanceToDestination.setVisibility(View.VISIBLE);

        final View view = getPagerViewForIndex(pagerPositionWhenPaused);
        if (view != null) {
            final TextView currentInstructionDistance =
                    (TextView) view.findViewById(R.id.distance_instruction);
            currentInstructionDistance.setText(
                    DistanceFormatter.format(
                            route.getCurrentInstruction().getLiveDistanceToNext(), true));
        }
    }

    @Override
    public void onRouteComplete() {
        pager.setCurrentItem(instructions.size() - 1);
        voiceNavigationController.playInstruction(instructions.get(instructions.size() - 1));
        distanceToDestination.setDistance(0);
        footerWrapper.setVisibility(View.GONE);
    }

    public View getPagerViewForIndex(int index) {
        return pager.findViewWithTag(RouteAdapter.TAG_BASE + String.valueOf(index));
    }

    private void logForDebugging(Location location, Location correctedLocation) {
        Logger.logToDatabase(act, db, ROUTE_TAG, "RouteFragment::onLocationChangeLocation: "
                + "new corrected location: " + correctedLocation.toString()
                + " from original: " + location.toString());
        Logger.logToDatabase(act, db, ROUTE_TAG, "RouteFragment::onLocationChangeLocation: " +
                "threshold: " + String.valueOf(getAdvanceRadius()));
        for (Instruction instruction : instructions) {
            Logger.logToDatabase(act, db, ROUTE_TAG, "RouteFragment::onLocationChangeLocation: " +
                    "turnPoint: " + instruction.toString());
        }
    }

    public boolean setRoute(final Route route) {
        if (route != null && route.foundRoute()) {
            this.route = route;
            this.instructions = route.getRouteInstructions();
            storeRouteInDatabase(route.getRawRoute());
            mapController.setMapPerspectiveForInstruction(instructions.get(0));
            routeEngine.setRoute(route);
            routeEngine.setListener(this);
        } else {
            return false;
        }
        return true;
    }

    public void storeRouteInDatabase(JSONObject rawRoute) {
        ContentValues insertValues = new ContentValues();
        routeId = UUID.randomUUID().toString();
        insertValues.put(COLUMN_TABLE_ID, routeId);
        insertValues.put(COLUMN_RAW, rawRoute.toString());
        insertIntoDb(TABLE_ROUTES, null, insertValues);

        ContentValues routeGroupEntry = new ContentValues();
        routeGroupEntry.put(COLUMN_ROUTE_ID, routeId);
        routeGroupEntry.put(COLUMN_GROUP_ID, groupId);
        insertIntoDb(TABLE_ROUTE_GROUP, null, routeGroupEntry);
    }

    private void storeRoute() {
        if (route != null) {
            ArrayList<Location> geometry = route.getGeometry();
            ArrayList<ContentValues> databaseValues = new ArrayList<ContentValues>();
            for (int index = 0; index < geometry.size(); index++) {
                Location location = geometry.get(index);
                databaseValues.add(buildContentValues(location, index));
            }
            insertIntoDb(TABLE_ROUTE_GEOMETRY, null, databaseValues);
        }
    }

    private ContentValues buildContentValues(Location location, int pos) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_TABLE_ID, UUID.randomUUID().toString());
        values.put(COLUMN_ROUTE_ID, routeId);
        values.put(COLUMN_POSITION, pos);
        values.put(COLUMN_LAT, location.getLatitude());
        values.put(COLUMN_LNG, location.getLongitude());
        return values;
    }

    public Route getRoute() {
        return route;
    }

    @Override
    public void onInstructionSelected(int index) {
        pager.setCurrentItem(index, true);
    }

    @Override
    public void onPageScrolled(int i, float v, int i2) {
        if (pager.getCurrentItem() == pagerPositionWhenPaused) {
            setCurrentPagerItemStyling(pagerPositionWhenPaused);
            if (!isPaging) {
                resumeAutoPaging();
            }
        }
    }

    @Override
    public void onPageSelected(int i) {
        if (!isPaging) {
            mapController.setMapPerspectiveForInstruction(instructions.get(i));
        } else {
            setCurrentPagerItemStyling(i);
        }
        notificationCreator.createNewNotification(
                SimpleFeatureHelper.getMarker(simpleFeature).title,
                instructions.get(i).getFullInstruction(getActivity()));
    }

    @Override
    public void onPageScrollStateChanged(int i) {
    }

    public int getNumberOfLocationsForAverageSpeed() {
        return getDefaultSharedPreferences(act).
                getInt(getString(R.string.settings_number_of_locations_for_average_speed_key),
                        R.integer.number_of_locations_for_average_speed);
    }

    public float getAverageSpeed() {
        if (db == null) {
            return 0;
        }

        Cursor cursor = db.
                rawQuery("SELECT AVG(" + COLUMN_SPEED + ") as avg_speed "
                        + "from (select " + COLUMN_SPEED + " from "
                        + TABLE_LOCATIONS
                        + " where "
                        + COLUMN_ROUTE_ID
                        + " = '"
                        + routeId
                        + "' ORDER BY time DESC LIMIT "
                        + getNumberOfLocationsForAverageSpeed()
                        + ")", null);
        cursor.moveToFirst();
        return cursor.getFloat(0);
    }

    private void storeLocationInfo(Location location, Location correctedLocation) {
        insertIntoDb(TABLE_LOCATIONS, null,
                valuesForLocationCorrection(location,
                        correctedLocation, instructions.get(pager.getCurrentItem()), routeId));
    }

    private void insertIntoDb(String table, String nullHack,
            ArrayList<ContentValues> contentValueCollection) {
        if (db == null) {
            return;
        }

        try {
            db.beginTransaction();
            for (ContentValues values : contentValueCollection) {
                insertIntoDb(table, nullHack, values);
            }
            db.setTransactionSuccessful();
            db.endTransaction();
        } catch (IllegalStateException e) {
            Mint.logException(e);
        }
    }

    private void insertIntoDb(String table, String nullHack, ContentValues contentValues) {
        if (db == null) {
            return;
        }

        try {
            long result = db.insert(table, nullHack, contentValues);
            if (result < 0) {
                Logger.e("error inserting into db");
            }
        } catch (IllegalStateException e) {
            Mint.logException(e);
        }
    }

    public void turnAutoPageOff() {
        if (isPaging) {
            pagerPositionWhenPaused = pager.getCurrentItem();
        }
        isPaging = false;
        resume.setVisibility(View.VISIBLE);
        voiceNavigationController.mute();
    }

    public void resumeAutoPaging() {
        pager.setCurrentItem(pagerPositionWhenPaused);
        setCurrentPagerItemStyling(pagerPositionWhenPaused);
        setPerspectiveForCurrentInstruction();
        resume.setVisibility(View.GONE);
        currentXCor = mapFragment.getMap().getMapPosition().getX();
        isPaging = true;
        voiceNavigationController.unmute();
    }

    private void setPerspectiveForCurrentInstruction() {
        int current = pagerPositionWhenPaused > 0 ? pagerPositionWhenPaused - 1 : 0;
        mapController.setMapPerspectiveForInstruction(instructions.get(current));
    }

    @Subscribe
    public void onLocationUpdate(LocationUpdateEvent event) {
        onLocationChanged(event.getLocation());
    }

    @Override
    public void success(final Route route) {
        if (setRoute(route)) {
            act.hideLoadingIndicator();
            hideLocateButtonAndAttribution();
            isRouting = false;
            if (!isAdded()) {
                act.getSupportFragmentManager().beginTransaction()
                        .addToBackStack(null)
                        .add(R.id.routes_container, this, TAG)
                        .commit();
            } else {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        final RouteAdapter adapter = new RouteAdapter(act, instructions, fragment);
                        adapter.setDestinationName(simpleFeature.getProperty(TEXT));
                        pager.setAdapter(adapter);
                        notificationCreator.createNewNotification(
                                SimpleFeatureHelper.getMarker(simpleFeature).title,
                                instructions.get(0).getFullInstruction(getActivity()));
                        setCurrentPagerItemStyling(0);
                    }
                });
            }
            storeRoute();
        } else {
            Toast.makeText(act, act.getString(R.string.no_route_found), Toast.LENGTH_LONG).show();
        }
    }

    private void setCurrentPagerItemStyling(int page) {
        if (page == instructions.size() - 1) {
            adapter.setBackgroundColorComplete(pager.findViewWithTag("Instruction_" + page));
        } else {
            adapter.setBackgroundColorActive(pager.findViewWithTag("Instruction_" + page));
        }

        adapter.setTurnIcon(pager.findViewWithTag("Instruction_" + page),
                DisplayHelper.getRouteDrawable(pager.getContext(),
                        instructions.get(page).getTurnInstruction(),
                        DisplayHelper.IconStyle.STANDARD));
        resetPagerItemStyling(page);
    }

    private void resetPagerItemStyling(int page) {
        if (page > 0) {
            page--;
            adapter.setTurnIcon(pager.findViewWithTag("Instruction_" + page),
                    DisplayHelper.getRouteDrawable(pager.getContext(),
                            instructions.get(page).getTurnInstruction(),
                            DisplayHelper.IconStyle.GRAY));

            adapter.setBackgroundColorInactive(pager.findViewWithTag("Instruction_" + page));
        }
    }

    private void markReadyForUpload() {
        if (db == null) {
            return;
        }

        ContentValues cv = new ContentValues();
        cv.put(DatabaseHelper.COLUMN_READY_FOR_UPLOAD, 1);
        try {
            db.update(TABLE_GROUPS, cv, COLUMN_TABLE_ID + " = ?",
                    new String[] { groupId });
        } catch (IllegalStateException e) {
            Mint.logException(e);
        }
    }

    @Override
    public void failure(int statusCode) {
        isRouting = false;
        onServerError(statusCode);
    }

    public String getGPXDescription() {
        if (instructions != null && instructions.size() >= 1) {
            Instruction firstInstruction = instructions.get(0);
            String destination = SimpleFeatureHelper.getFullLocationString(simpleFeature);
            return new StringBuilder().append("Route between: ")
                    .append(formatInstructionForDescription(firstInstruction))
                    .append(" -> ")
                    .append(destination).toString();
        } else {
            return "Route without instructions";
        }
    }

    private String formatInstructionForDescription(Instruction instruction) {
        Location loc = instruction.getLocation();
        String locationName = instruction.getSimpleInstruction(getActivity())
                .replace(instruction.getHumanTurnInstruction(getActivity()), "");
        String latLong = " [" + loc.getLatitude() + ", " + loc.getLongitude() + ']';
        String startLocationString = locationName + latLong;
        return startLocationString;
    }

    private void initDebugView(View view) {
        debugView = (DebugView) view.findViewById(R.id.debugging);
        if (act.isInDebugMode()) {
            debugView.setVisibility(View.VISIBLE);
        }
    }

    public void initSlideLayout(View view) {
        setSlideLayout((SlidingUpPanelLayout) view.findViewById(R.id.sliding_layout));
        getSlideLayout().setDragView(view.findViewById(R.id.drag_area));
        getSlideLayout().setTouchEnabled(false);
        addSlideLayoutTouchListener();
        getSlideLayout().setPanelSlideListener(getPanelSlideListener());
        if (shouldRestoreDirectionList) {
            showDirectionListFragmentInExpanded();
            shouldRestoreDirectionList = false;
        }
    }

    public SlidingUpPanelLayout.PanelSlideListener getPanelSlideListener() {
        return (new SlidingUpPanelLayout.PanelSlideListener() {
            @Override
            public void onPanelSlide(View panel, float slideOffset) {
                if (slideOffset > SLIDING_PANEL_OFFSET_MARGIN) {
                    if (directionListFragment == null) {
                        showDirectionListFragmentInExpanded();
                    }
                }
                if (slideOffset < SLIDING_PANEL_OFFSET_MARGIN && directionListFragment != null) {
                    hideDirectionListFragment();
                    getSlideLayout().setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
                }
                if (slideOffset == SLIDING_PANEL_OFFSET_OPEN) {
                    getSlideLayout().setTouchEnabled(false);
                }
            }

            @Override
            public void onPanelExpanded(View panel) {
            }

            @Override
            public void onPanelCollapsed(View panel) {
                getSlideLayout().setTouchEnabled(false);
            }

            @Override
            public void onPanelAnchored(View panel) {
            }

            @Override public void onPanelHidden(View view) {
            }
        });
    }

    private void showDirectionListFragmentInExpanded() {
        directionListFragment = DirectionListFragment.
                newInstance(route.getRouteInstructions(),
                        new DirectionListFragment.DirectionListener() {
                            @Override
                            public void onInstructionSelected(int index) {
                                instructionSelectedAction(index);
                            }
                        }, simpleFeature, false);
        getChildFragmentManager().beginTransaction()
                .replace(R.id.footer_wrapper, directionListFragment
                        , DirectionListFragment.TAG)
                .disallowAddToBackStack()
                .commit();
    }

    private void instructionSelectedAction(int index) {
        turnAutoPageOff();
        pager.setCurrentItem(index);
    }

    private void hideDirectionListFragment() {
        if (directionListFragment != null) {
            getChildFragmentManager()
                    .beginTransaction()
                    .disallowAddToBackStack()
                    .remove(directionListFragment)
                    .commitAllowingStateLoss();
        }
        directionListFragment = null;
    }

    private void addSlideLayoutTouchListener() {
        footerWrapper.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                getSlideLayout().setTouchEnabled(true);
                return false;
            }
        });
    }

    public void collapseSlideLayout() {
        if (slideLayoutIsExpanded()) {
            getSlideLayout().setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        }
    }

    public boolean slideLayoutIsExpanded() {
        return getSlideLayout().getPanelState() == SlidingUpPanelLayout.PanelState.EXPANDED;
    }

    public SlidingUpPanelLayout getSlideLayout() {
        return slideLayout;
    }

    public void setSlideLayout(SlidingUpPanelLayout slideLayout) {
        this.slideLayout = slideLayout;
    }

    public void pageToNext(int position) {
        pager.setCurrentItem(position + 1);
    }

    public void pageToPrevious(int position) {
        pager.setCurrentItem(position - 1);
    }

    private void showLocateButton() {
        act.findViewById(R.id.locate_button).setVisibility(View.VISIBLE);
    }

    private void hideLocateButtonAndAttribution() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (act != null) {
                    act.findViewById(R.id.locate_button).setVisibility(View.GONE);
                    act.findViewById(R.id.attribution).setVisibility(View.GONE);
                }
            }
        }, 100);
    }

    public class MapOnTouchListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            boolean oneFinger = event.getPointerCount() < 2;
            boolean enoughChange = Math.abs(mapFragment.getMap().getMapPosition()
                    .getX() - currentXCor) > MIN_CHANGE_FOR_SHOW_RESUME;
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (oneFinger && enoughChange) {
                    turnAutoPageOff();
                } else if (isPaging) {
                    currentXCor = mapFragment.getMap().getMapPosition().getX();
                    resume.setVisibility(View.GONE);
                }
            }
            return false;
        }
    }

    public void setCurrentXCor(float x) {
        currentXCor = x;
    }

    public boolean onBackAction() {
        if (slideLayoutIsExpanded()) {
            collapseSlideLayout();
            return false;
        } else if (resume.getVisibility() == View.VISIBLE) {
            resume.callOnClick();
            return false;
        } else {
            return true;
        }
    }

    private Map.UpdateListener mapListener = new Map.UpdateListener() {
        @Override
        public void onMapEvent(final Event e, MapPosition mapPosition) {
            if (activeTask != null) {
                activeTask.cancel(true);
            }
            activeTask = new DrawPathTask(app);
            activeTask.execute(route.getGeometry());
        }
    };

    private void setupLinedrawing() {
        mapController.getMap().events.bind(mapListener);
    }

    private void teardownLinedrawing() {
        mapController.getMap().events.unbind(mapListener);
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        mapController.clearLines();
        mapFragment.updateMap();
    }
}
