package com.mapzen.open.event;

import com.mapzen.pelias.SimpleFeature;

public class RoutePreviewEvent {
    private SimpleFeature simpleFeature;

    public RoutePreviewEvent(SimpleFeature simpleFeature) {
        this.simpleFeature = simpleFeature;
    }

    public SimpleFeature getSimpleFeature() {
        return simpleFeature;
    }
}
