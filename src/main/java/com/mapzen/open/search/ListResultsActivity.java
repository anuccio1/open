package com.mapzen.open.search;

import com.mapzen.pelias.SimpleFeature;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.view.MenuItem;

import java.util.ArrayList;

public class ListResultsActivity extends ActionBarActivity {
    public static final String EXTRA_FEATURE_LIST = "com.mapzen.search.features";
    public static final String EXTRA_SEARCH_TERM = "com.mapzen.search.term";
    public static final String EXTRA_INDEX = "com.mapzen.search.index";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ArrayList<SimpleFeature> simpleFeatures =
                getIntent().getParcelableArrayListExtra(EXTRA_FEATURE_LIST);
        final ListResultsFragment fragment = ListResultsFragment.newInstance(simpleFeatures,
                getIntent().getStringExtra(EXTRA_SEARCH_TERM));
        getSupportFragmentManager().beginTransaction().add(android.R.id.content, fragment).commit();
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
