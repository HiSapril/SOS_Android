package com.javafinal.viewmodel;

import com.google.gson.Gson;
import com.javafinal.model.LocationCurrent;
import com.javafinal.model.SosRequest;
import com.javafinal.util.SosMapData;
import org.junit.Test;
import static org.junit.Assert.*;

public class SosMapDataTest {
    @Test public void missingCoordinatesDoNotBecomeAnInventedZeroMarker() {
        SosRequest sos = new Gson().fromJson("{\"id\":\"s1\",\"status\":\"ACCEPTED\"}", SosRequest.class);
        assertFalse(sos.hasInitialLocation());
        assertNull(SosMapData.victim(sos));
        assertNull(SosMapData.rescuer(sos));
    }
    @Test public void actualZeroCoordinatesArePreservedAndCurrentLocationTakesPrecedence() {
        SosRequest sos = new Gson().fromJson("{\"initial_lat\":0,\"initial_lng\":0}", SosRequest.class);
        assertTrue(sos.hasInitialLocation());
        assertEquals(0, SosMapData.victim(sos).getLat(), 0);
        LocationCurrent current = new LocationCurrent(null, "s1", "VICTIM", 10.78, 106.7, 5, null);
        sos.setLocations(new SosRequest.LocationsPair(current, null));
        assertSame(current, SosMapData.victim(sos));
    }
}
