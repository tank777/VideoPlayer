/*
 * Copyright 2014 Mario Guggenberger <mg@protyposis.net>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.protyposis.android.mediaplayer.dash;

import android.net.Uri;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.protyposis.android.mediaplayer.UriSource;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by maguggen on 27.08.2014.
 */
public class DashParser {

    private static final String TAG = DashParser.class.getSimpleName();

    private static Pattern PATTERN_TIME = Pattern.compile("PT((\\d+)H)?((\\d+)M)?((\\d+(\\.\\d+)?)S)");
    private static Pattern PATTERN_TEMPLATE = Pattern.compile("\\$(\\w+)(%0\\d+d)?\\$");
    private static DateFormat ISO8601UTC;

    static {
        ISO8601UTC = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        ISO8601UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static class SegmentTemplate {

        private static class SegmentTimelineEntry {
            /**
             * The segment start time in timescale units. Optional value.
             * Default is 0 for the first element in a timeline, and t+d*(r+1) of previous element
             * for all subsequent elements.
             */
            long t;

            /**
             * The segment duration in timescale units.
             */
            long d;

            /**
             * The segment repeat count. Specifies the number of contiguous segments with
             * duration d, that follow the first segment (r=2 means first segment plus two
             * following segments, a total of 3).
             * A negative number tells that there are contiguous segments until the start of the
             * next timeline entry, the end of the period, or the next MPD update.
             * The default is 0.
             */
            int r;

            long calculateDuration() {
                return d * (r + 1);
            }
        }

        long presentationTimeOffsetUs;
        long timescale;
        String init;
        String media;
        long duration;
        int startNumber;
        List<SegmentTimelineEntry> timeline = new ArrayList<>();

        long calculateDurationUs() {
            return calculateUs(duration, timescale);
        }

        boolean hasTimeline() {
            return !timeline.isEmpty();
        }
    }

    private Date serverDate;

    /**
     * Parses an MPD XML file. This needs to be executed off the main thread, else a
     * NetworkOnMainThreadException gets thrown.
     * @param source the URl of an MPD XML file
     * @param httpClient the http client instance to use for the request
     * @return a MPD object
     * @throws android.os.NetworkOnMainThreadException if executed on the main thread
     */
    public MPD parse(UriSource source, OkHttpClient httpClient) throws DashParserException {
        MPD mpd = null;

        Headers.Builder headers = new Headers.Builder();
        if(source.getHeaders() != null && !source.getHeaders().isEmpty()) {
            for(String name : source.getHeaders().keySet()) {
                headers.add(name, source.getHeaders().get(name));
            }
        }

        Uri uri = source.getUri();

        Request.Builder request = new Request.Builder()
                .url(uri.toString())
                .headers(headers.build());

        try {
            Response response = httpClient.newCall(request.build()).execute();
            if(!response.isSuccessful()) {
                throw new IOException("error requesting the MPD");
            }

            // Determine this MPD's default BaseURL by removing the last path segment (which is the MPD file)
            Uri baseUrl = Uri.parse(uri.toString().substring(0, uri.toString().lastIndexOf("/") + 1));

            // Get the current datetime from the server for live stream time syncing
            serverDate = response.headers().getDate("Date");

            // Parse the MPD file
            mpd = parse(response.body().byteStream(), baseUrl);
        } catch (IOException e) {
            Log.e(TAG, "error downloading the MPD", e);
            throw new DashParserException("error downloading the MPD", e);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "error parsing the MPD", e);
            throw new DashParserException("error parsing the MPD", e);
        }

        return mpd;
    }

    /**
     * Parses an MPD XML file. This needs to be executed off the main thread, else a
     * NetworkOnMainThreadException gets thrown.
     * @param source the URl of an MPD XML file
     * @return a MPD object
     * @throws android.os.NetworkOnMainThreadException if executed on the main thread
     */
    public MPD parse(UriSource source) throws DashParserException {
        return parse(source, new OkHttpClient());
    }

    private MPD parse(InputStream in, Uri baseUrl) throws XmlPullParserException, IOException, DashParserException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);

            MPD mpd = new MPD();
            Period currentPeriod = null;

            int type = 0;
            while((type = parser.next()) >= 0) {
                if(type == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();

                    if(tagName.equals("MPD")) {
                        mpd.isDynamic = getAttributeValue(parser, "type", "static").equals("dynamic");

                        if (mpd.isDynamic) {
                            Log.i(TAG, "dynamic MPD not supported yet, but giving it a try...");
                            // Set a dummy duration to get the stream to work for some time
                            mpd.mediaPresentationDurationUs = 1l /* h */ * 60 * 60 * 1000000;
                            mpd.timeShiftBufferDepthUs = getAttributeValueTime(parser, "timeShiftBufferDepth", "PT0S");
                            mpd.maxSegmentDurationUs = getAttributeValueTime(parser, "maxSegmentDuration", "PT0S");
                            mpd.suggestedPresentationDelayUs = getAttributeValueTime(parser, "suggestedPresentationDelay", "PT0S");
                            // TODO add support for dynamic streams with unknown duration

                            String date = getAttributeValue(parser, "availabilityStartTime");
                            try {
                                if (date.length() == 19) {
                                    date = date + "Z";
                                }
                                mpd.availabilityStartTime = ISO8601UTC.parse(date.replace("Z", "+00:00"));
                            } catch (ParseException e) {
                                Log.e(TAG, "unable to parse date: " + date);
                            }
                        } else { // type == static
                            mpd.mediaPresentationDurationUs = getAttributeValueTime(parser, "mediaPresentationDuration");
                        }
                        mpd.minBufferTimeUs = getAttributeValueTime(parser, "minBufferTime");
                    } else if(tagName.equals("Period")) {
                        currentPeriod = new Period();
                        currentPeriod.id = getAttributeValue(parser, "id");
                        currentPeriod.startUs = getAttributeValueTime(parser, "start");
                        currentPeriod.durationUs = getAttributeValueTime(parser, "duration");
                        currentPeriod.bitstreamSwitching = getAttributeValueBoolean(parser, "bitstreamSwitching");
                    } else if(tagName.equals("BaseURL")) {
                        baseUrl = extendUrl(baseUrl, parser.nextText());
                        Log.d(TAG, "base url: " + baseUrl);
                    } else if(tagName.equals("AdaptationSet")) {
                        currentPeriod.adaptationSets.add(readAdaptationSet(mpd, currentPeriod, baseUrl, parser));
                    }
                } else if(type == XmlPullParser.END_TAG) {
                    String tagName = parser.getName();
                    if(tagName.equals("MPD")) {
                        break;
                    } else if(tagName.equals("Period")) {
                        mpd.periods.add(currentPeriod);
                        currentPeriod = null;
                    }
                }
            }

            Log.d(TAG, mpd.toString());

            return mpd;
        } finally {
            in.close();
        }
    }

    private AdaptationSet readAdaptationSet(MPD mpd, Period period, Uri baseUrl, XmlPullParser parser)
            throws XmlPullParserException, IOException, DashParserException {
        AdaptationSet adaptationSet = new AdaptationSet();

        adaptationSet.group = getAttributeValueInt(parser, "group");
        adaptationSet.mimeType = getAttributeValue(parser, "mimeType");
        adaptationSet.maxWidth = getAttributeValueInt(parser, "maxWidth");
        adaptationSet.maxHeight = getAttributeValueInt(parser, "maxHeight");
        adaptationSet.par = getAttributeValueRatio(parser, "par");

        SegmentTemplate segmentTemplate = null;

        int type = 0;
        while((type = parser.next()) >= 0) {
            if(type == XmlPullParser.START_TAG) {
                String tagName = parser.getName();

                if(tagName.equals("SegmentTemplate")) {
                    segmentTemplate = readSegmentTemplate(parser, baseUrl, null);
                } else if(tagName.equals("Representation")) {
                    try {
                        adaptationSet.representations.add(readRepresentation(
                                mpd, period, adaptationSet, baseUrl, parser, segmentTemplate));
                    } catch (Exception e) {
                        Log.e(TAG, "error reading representation: " + e.getMessage(), e);
                    }
                }
            } else if(type == XmlPullParser.END_TAG) {
                String tagName = parser.getName();
                if(tagName.equals("AdaptationSet")) {
                    return adaptationSet;
                }
            }
        }

        throw new DashParserException("invalid state");
    }

    private Representation readRepresentation(MPD mpd, Period period, AdaptationSet adaptationSet,
                                              Uri baseUrl, XmlPullParser parser,
                                              SegmentTemplate segmentTemplate)
            throws XmlPullParserException, IOException, DashParserException {
        Representation representation = new Representation();

        representation.id = getAttributeValue(parser, "id");
        representation.codec = getAttributeValue(parser, "codecs");
        representation.mimeType = getAttributeValue(parser, "mimeType", adaptationSet.mimeType);
        if(representation.mimeType.startsWith("video/")) {
            representation.width = getAttributeValueInt(parser, "width");
            representation.height = getAttributeValueInt(parser, "height");
            representation.sar = getAttributeValueRatio(parser, "sar");
        }
        representation.bandwidth = getAttributeValueInt(parser, "bandwidth");

        int type = 0;
        while((type = parser.next()) >= 0) {
            String tagName = parser.getName();

            if(type == XmlPullParser.START_TAG) {
                if (tagName.equals("Initialization")) {
                    String sourceURL = getAttributeValue(parser, "sourceURL");
                    String range = getAttributeValue(parser, "range");

                    sourceURL = sourceURL != null ? extendUrl(baseUrl, sourceURL).toString() : baseUrl.toString();

                    representation.initSegment = new Segment(sourceURL, range);
                    Log.d(TAG, "Initialization: " + representation.initSegment.toString());
                } else if(tagName.equals("SegmentList")) {
                    long timescale = getAttributeValueLong(parser, "timescale", 1);
                    long duration = getAttributeValueLong(parser, "duration");
                    representation.segmentDurationUs = (long)(((double)duration / timescale) * 1000000d);
                } else if(tagName.equals("SegmentURL")) {
                    String media = getAttributeValue(parser, "media");
                    String mediaRange = getAttributeValue(parser, "mediaRange");
                    String indexRange = getAttributeValue(parser, "indexRange");

                    media = media != null ? extendUrl(baseUrl, media).toString() : baseUrl.toString();

                    representation.segments.add(new Segment(media, mediaRange));

                    if(indexRange != null) {
                        Log.v(TAG, "skipping unsupported indexRange in SegmentURL");
                    }
                } else if(tagName.equals("SegmentBase")) {
                    String indexRange = getAttributeValue(parser, "indexRange");
                    if(indexRange != null) {
                        throw new DashParserException("single segment / indexRange is not supported yet");
                    }
                } else if(tagName.equals("SegmentTemplate")) {
                    // Overwrite passed template with newly parsed one
                    segmentTemplate = readSegmentTemplate(parser, baseUrl, segmentTemplate);
                } else if(tagName.equals("BaseURL")) {
                    baseUrl = extendUrl(baseUrl, parser.nextText());
                    Log.d(TAG, "new base url: " + baseUrl);
                } else if(tagName.equals("RepresentationIndex")) {
                    throw new DashParserException("RepresentationIndex is not supported yet");
                }
            } else if(type == XmlPullParser.END_TAG) {
                if(tagName.equals("Representation")) {
                    if(!representation.segments.isEmpty()) {
                        // a SegmentList has been parsed, nothing to do here
                    }
                    else if(segmentTemplate != null) {
                        // We have a SegmentTemplate, expand it to a list of segments

                        if(segmentTemplate.hasTimeline()) {
                            if(segmentTemplate.timeline.size() > 1) {
                                /* TODO Add support for individual segment lengths
                                 * To support multiple timeline entries, the segmentDurationUs
                                 * must be moved from the representation to the individual segments,
                                 * because their length is not necessarily constant and can change
                                 * over time.
                                 */
                                throw new DashParserException("timeline with multiple entries is not supported yet");
                            }

                            SegmentTemplate.SegmentTimelineEntry current, previous, next;
                            for(int i = 0; i < segmentTemplate.timeline.size(); i++) {
                                current = segmentTemplate.timeline.get(i);
                                //previous = i > 0 ? segmentTemplate.timeline.get(i - 1) : null;
                                next = i < segmentTemplate.timeline.size() - 1 ? segmentTemplate.timeline.get(i + 1) : null;

                                int repeat = current.r;
                                if(repeat < 0) {
                                    long duration = next != null ? next.t - current.t :
                                            calculateTimescaleTime(mpd.mediaPresentationDurationUs, segmentTemplate.timescale) - current.t;
                                    repeat = (int)(duration / current.d) - 1;
                                }

                                representation.segmentDurationUs = calculateUs(current.d, segmentTemplate.timescale);

                                // init segment
                                String processedInitUrl = processMediaUrl(
                                        segmentTemplate.init, representation.id, null, representation.bandwidth, null);
                                representation.initSegment = new Segment(processedInitUrl);

                                // media segments
                                long time = current.t;
                                for (int number = segmentTemplate.startNumber; number < repeat + 1; number++) {
                                    String processedMediaUrl = processMediaUrl(
                                            segmentTemplate.media, representation.id, number, representation.bandwidth, time);
                                    representation.segments.add(new Segment(processedMediaUrl));
                                    time += current.d;
                                }
                            }
                        }
                        else {
                            representation.segmentDurationUs = segmentTemplate.calculateDurationUs();
                            int numSegments = (int) Math.ceil((double) mpd.mediaPresentationDurationUs / representation.segmentDurationUs);
                            int dynamicStartNumberOffset = 0;

                            if(mpd.isDynamic) {
                                // Simulate availabilityStartTime support by converting it to a startNumber
                                Date now = new Date();
                                Calendar calendar = Calendar.getInstance();

                                calendar.setTime(now);
                                calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
                                now = calendar.getTime();

                                // sync local time with server time (from http date header)
                                if(serverDate != null) {
                                    now = serverDate;
                                }

                                /* Calculate the time delta between the availability start time
                                 * and the current time, for that we know at which position we
                                 * currently are in the live stream. */
                                long availabilityDeltaTimeUs = (now.getTime() - mpd.availabilityStartTime.getTime()) * 1000;

                                // shift by the period start
                                availabilityDeltaTimeUs -= period.startUs;

                                // shift by the presentationTimeOffset
                                availabilityDeltaTimeUs -= segmentTemplate.presentationTimeOffsetUs;

                                // go back in time by the buffering period (else the segments to be buffered are not available yet)
                                availabilityDeltaTimeUs -= Math.max(mpd.minBufferTimeUs, 10 * 1000000L);

                                // go back in time by the suggested presentation delay
                                availabilityDeltaTimeUs -= mpd.suggestedPresentationDelayUs;

                                // convert the delta time to the number of corresponding segments
                                // add it to the start number (which by default is 0 if not specified)
                                dynamicStartNumberOffset = (int)(availabilityDeltaTimeUs / representation.segmentDurationUs);
                            }

                            // init segment
                            String processedInitUrl = processMediaUrl(
                                    segmentTemplate.init, representation.id, null, representation.bandwidth, null);
                            representation.initSegment = new Segment(processedInitUrl);

                            // media segments
                            for (int i = segmentTemplate.startNumber + dynamicStartNumberOffset; i < segmentTemplate.startNumber + dynamicStartNumberOffset + numSegments; i++) {
                                String processedMediaUrl = processMediaUrl(
                                        segmentTemplate.media, representation.id, i, representation.bandwidth, null);
                                representation.segments.add(new Segment(processedMediaUrl));
                            }
                        }
                    }
                    else {
                        /* When there is no SegmentList or SegmentTemplate, the only option left is
                         * a single file/segment representation. */

                        // Subtitle are not supported yet and can be ignored
                        if(representation.mimeType != null && representation.mimeType.startsWith("text/")) {
                            Log.i(TAG, "unsupported subtitle representation");
                        }
                        // Video and audio representations are vital for the player and cannot be ignored
                        else {
                            throw new DashParserException("single-segment representations are not supported yet");
                            // TODO implement single-file/single-segment support
                            // TODO add SegmentBase and sidx downloading and parsing
                        }
                    }

                    Log.d(TAG, representation.toString());

                    return representation;
                }
            }
        }

        throw new DashParserException("invalid state");
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    private SegmentTemplate readSegmentTemplate(XmlPullParser parser, Uri baseUrl, SegmentTemplate parent)
            throws IOException, XmlPullParserException, DashParserException {
        SegmentTemplate st = new SegmentTemplate();

        // Read properties from template or carry them over from a parent

        st.timescale = getAttributeValueLong(parser, "timescale", parent != null ? parent.timescale : 1);
        long presentationTimeOffset = getAttributeValueLong(parser, "presentationTimeOffsetUs", parent != null ? parent.presentationTimeOffsetUs : 0);
        st.presentationTimeOffsetUs = calculateUs(presentationTimeOffset, st.timescale);
        st.duration = getAttributeValueLong(parser, "duration", parent != null ? parent.duration : 0);
        st.startNumber = getAttributeValueInt(parser, "startNumber", parent != null ? parent.startNumber : 1);

        String initialization = getAttributeValue(parser, "initialization");
        if(initialization != null) {
            st.init = extendUrl(baseUrl, initialization).toString();
        } else if(parent != null) {
            st.init = parent.init;
        }

        String media = getAttributeValue(parser, "media");
        if(media != null) {
            st.media = extendUrl(baseUrl, media).toString();
        } else if(parent != null) {
            st.media = parent.media;
        }

        int type = 0;
        while((type = parser.next()) >= 0) {
            if(type == XmlPullParser.START_TAG) {
                String tagName = parser.getName();

                if(tagName.equals("S")) {
                    SegmentTemplate.SegmentTimelineEntry e = new SegmentTemplate.SegmentTimelineEntry();

                    long defaultTime = 0;
                    if(!st.timeline.isEmpty()) {
                        SegmentTemplate.SegmentTimelineEntry previous = st.timeline.get(st.timeline.size() - 1);
                        defaultTime = previous.t + previous.calculateDuration();
                    }

                    e.t = getAttributeValueLong(parser, "t", defaultTime);
                    e.d = getAttributeValueLong(parser, "d");
                    e.r = getAttributeValueInt(parser, "r");

                    st.timeline.add(e);
                } else if(tagName.equals("RepresentationIndex")) {
                    throw new DashParserException("RepresentationIndex is not supported yet");
                }
            } else if(type == XmlPullParser.END_TAG) {
                String tagName = parser.getName();
                if(tagName.equals("SegmentTemplate")) {
                    return st;
                }
            }
        }

        throw new DashParserException("invalid state");
    }

    /**
     * Parse a timestamp and return its duration in microseconds.
     * http://en.wikipedia.org/wiki/ISO_8601#Durations
     */
    private static long parseTime(String time) {
        Matcher matcher = PATTERN_TIME.matcher(time);

        if(matcher.matches()) {
            long hours = 0;
            long minutes = 0;
            double seconds = 0;

            String group = matcher.group(2);
            if (group != null) {
                hours = Long.parseLong(group);
            }
            group = matcher.group(4);
            if (group != null) {
                minutes = Long.parseLong(group);
            }
            group = matcher.group(6);
            if (group != null) {
                seconds = Double.parseDouble(group);
            }

            return (long) (seconds * 1000 * 1000)
                    + minutes * 60 * 1000 * 1000
                    + hours * 60 * 60 * 1000 * 1000;
        }

        return -1;
    }

    /**
     * Extends an URL with an extended path if the extension is relative, or replaces the entire URL
     * with the extension if it is absolute.
     */
    private static Uri extendUrl(Uri url, String urlExtension) {
        urlExtension = urlExtension.replace(" ", "%20"); // Convert spaces

        Uri newUrl = Uri.parse(urlExtension);

        if(newUrl.isRelative()) {
            /* Uri.withAppendedPath appends the extension to the end of the "real" server path,
             * instead of the end of the uri string.
             * Example: http://server.com/foo?file=http://server2.net/ + file1.mp4
             *           => http://server.com/foo/file1.mp4?file=http://server2.net/
             * To avoid this, we need to join as strings instead. */
            newUrl = Uri.parse(url.toString() + urlExtension);
        }
        return newUrl;
    }

    /**
     * Converts a time/timescale pair to microseconds.
     */
    private static long calculateUs(long time, long timescale) {
        return (long)(((double)time / timescale) * 1000000d);
    }

    private static long calculateTimescaleTime(long time, long timescale) {
        return (long)((time / 1000000d) * timescale);
    }

    private static String getAttributeValue(XmlPullParser parser, String name, String defValue) {
        String value = parser.getAttributeValue(null, name);
        return value != null ? value : defValue;
    }

    private static String getAttributeValue(XmlPullParser parser, String name) {
        return getAttributeValue(parser, name, null);
    }

    private static int getAttributeValueInt(XmlPullParser parser, String name) {
        return Integer.parseInt(getAttributeValue(parser, name, "0"));
    }

    private static int getAttributeValueInt(XmlPullParser parser, String name, int defValue) {
        return Integer.parseInt(getAttributeValue(parser, name, defValue+""));
    }

    private static long getAttributeValueLong(XmlPullParser parser, String name) {
        return Long.parseLong(getAttributeValue(parser, name, "0"));
    }

    private static long getAttributeValueLong(XmlPullParser parser, String name, long defValue) {
        return Long.parseLong(getAttributeValue(parser, name, defValue+""));
    }

    private static long getAttributeValueTime(XmlPullParser parser, String name) {
        return parseTime(getAttributeValue(parser, name, "PT0S"));
    }

    private static long getAttributeValueTime(XmlPullParser parser, String name, String defValue) {
        return parseTime(getAttributeValue(parser, name, defValue));
    }

    private static float getAttributeValueRatio(XmlPullParser parser, String name) {
        String value = getAttributeValue(parser, name);

        if(value != null) {
            String[] values = value.split(":");
            return (float)Integer.parseInt(values[0]) / Integer.parseInt(values[1]);
        }

        return 0;
    }

    private static boolean getAttributeValueBoolean(XmlPullParser parser, String name) {
        String value = getAttributeValue(parser, name, "false");
        return value.equals("true");
    }

    /**
     * Processes templates in media URLs.
     * 
     * Example: $RepresentationID$_$Number%05d$.ts
     *
     * 5.3.9.4.4 Template-based Segment URL construction
     * Table 16 - Identifiers for URL templates
     */
    private static String processMediaUrl(String url, String representationId,
                                          Integer number, Integer bandwidth, Long time) {
        // RepresentationID
        if(representationId != null) {
            url = url.replace("$RepresentationID$", representationId);
        }

        // Number, Bandwidth & Time with formatting support
        // The following block converts DASH segment URL templates to a Java String.format expression

        List<String> templates = Arrays.asList("Number", "Bandwidth", "Time");
        Matcher matcher = PATTERN_TEMPLATE.matcher(url);

        while(matcher.find()) {
            String template = matcher.group(1);
            String pattern = matcher.group(2);
            int index = templates.indexOf(template);

            if(pattern != null) {
                url = url.replace("$" + template + pattern + "$",
                        "%" + (index + 1) + "$" + pattern.substring(1));
            } else {
                // Table 16: If no format tag is present, a default format tag with width=1 shall be used.
                url = url.replace("$" + template + "$", "%" + (index + 1) + "$01d");
            }
        }

        url = String.format(url, number, bandwidth, time); // order must match templates list above

        // $$
        // Replace this at the end, else it breaks directly consecutive template expressions,
        // e.g. $Bandwidth$$Number$.
        url = url.replace("$$", "$");

        return url;
    }
}
