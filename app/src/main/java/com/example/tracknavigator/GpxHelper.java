package com.example.tracknavigator;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Minimal GPX 1.1 writer and reader for track points (control points). */
public final class GpxHelper {

    private static final SimpleDateFormat ISO8601;

    static {
        ISO8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        ISO8601.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private GpxHelper() {}

    public static void writeTrack(OutputStream out, String trackName, List<LatLngPoint> points)
            throws IOException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"TrackNavigator\" ");
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("  <trk><name>").append(escapeXml(trackName)).append("</name>\n");
        sb.append("    <trkseg>\n");
        String time = ISO8601.format(new Date());
        for (LatLngPoint p : points) {
            sb.append("      <trkpt lat=\"").append(p.latitude).append("\" lon=\"")
                    .append(p.longitude).append("\">\n");
            sb.append("        <time>").append(time).append("</time>\n");
            sb.append("      </trkpt>\n");
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Reads ordered control points: all trkpt in document order (first trkseg),
     * or wpt elements if no trkpt found.
     */
    public static List<LatLngPoint> readPoints(InputStream in)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, StandardCharsets.UTF_8.name());
        List<LatLngPoint> trkpts = new ArrayList<>();
        List<LatLngPoint> wpts = new ArrayList<>();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("trkpt".equals(name)) {
                    LatLngPoint p = parsePoint(parser);
                    if (p != null) {
                        trkpts.add(p);
                    }
                } else if ("wpt".equals(name)) {
                    LatLngPoint p = parsePoint(parser);
                    if (p != null) {
                        wpts.add(p);
                    }
                }
            }
            event = parser.next();
        }
        if (!trkpts.isEmpty()) {
            return trkpts;
        }
        return wpts;
    }

    private static LatLngPoint parsePoint(XmlPullParser parser) {
        String latStr = null;
        String lonStr = null;
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            String name = parser.getAttributeName(i);
            if ("lat".equals(name)) {
                latStr = parser.getAttributeValue(i);
            } else if ("lon".equals(name)) {
                lonStr = parser.getAttributeValue(i);
            }
        }
        if (latStr == null || lonStr == null) {
            return null;
        }
        try {
            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            return new LatLngPoint(lat, lon);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
