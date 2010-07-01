/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.Downloads;
import android.test.MoreAsserts;
import android.test.RenamingDelegatingContext;
import android.test.ServiceTestCase;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import tests.http.MockResponse;
import tests.http.MockWebServer;
import tests.http.RecordedRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This test exercises the entire download manager working together -- it requests downloads through
 * the {@link DownloadProvider}, just like a normal client would, and runs the
 * {@link DownloadService} with start intents.  It sets up a {@link MockWebServer} running on the
 * device to serve downloads.
 */
@LargeTest
public class DownloadManagerFunctionalTest extends ServiceTestCase<DownloadService> {
    private static final String LOG_TAG = "DownloadManagerFunctionalTest";

    private static final String PROVIDER_AUTHORITY = "downloads";
    private static final int RETRY_DELAY_MILLIS = 61 * 1000;
    private static final long REQUEST_TIMEOUT_MILLIS = 10 * 1000;
    private static final String FILE_CONTENT = "hello world";

    private static final int HTTP_OK = 200;
    private static final int HTTP_PARTIAL_CONTENT = 206;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    private MockWebServer mServer;
    // resolves requests to the DownloadProvider we set up
    private MockContentResolver mResolver;
    private TestContext mTestContext;
    private FakeSystemFacade mSystemFacade;

    /**
     * Context passed to the provider and the service.  Allows most methods to pass through to the
     * real Context (this is a LargeTest), with a few exceptions, including renaming file operations
     * to avoid file and DB conflicts (via RenamingDelegatingContext).
     */
    private static class TestContext extends RenamingDelegatingContext {
        private static final String FILENAME_PREFIX = "test.";

        private Context mRealContext;
        private Set<String> mAllowedSystemServices;
        private ContentResolver mResolver;

        boolean mHasServiceBeenStarted = false;
        FakeIConnectivityManager mFakeIConnectivityManager;

        public TestContext(Context realContext) {
            super(realContext, FILENAME_PREFIX);
            mRealContext = realContext;
            mAllowedSystemServices = new HashSet<String>(Arrays.asList(new String[] {
                    Context.NOTIFICATION_SERVICE,
                    Context.POWER_SERVICE,
            }));
            mFakeIConnectivityManager = new FakeIConnectivityManager();
        }

        public void setResolver(ContentResolver resolver) {
            mResolver = resolver;
        }

        /**
         * Direct DownloadService to our test instance of DownloadProvider.
         */
        @Override
        public ContentResolver getContentResolver() {
            assert mResolver != null;
            return mResolver;
        }

        /**
         * Stub some system services, allow access to others, and block the rest.
         */
        @Override
        public Object getSystemService(String name) {
            if (name.equals(Context.CONNECTIVITY_SERVICE)) {
                return new ConnectivityManager(mFakeIConnectivityManager);
            }
            if (mAllowedSystemServices.contains(name)) {
                return mRealContext.getSystemService(name);
            }
            return super.getSystemService(name);
        }

        /**
         * Record when DownloadProvider starts DownloadService.
         */
        @Override
        public ComponentName startService(Intent service) {
            if (service.getComponent().getClassName().equals(DownloadService.class.getName())) {
                mHasServiceBeenStarted = true;
                return service.getComponent();
            }
            throw new UnsupportedOperationException("Unexpected service: " + service);
        }
    }

    public DownloadManagerFunctionalTest() {
        super(DownloadService.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        Context realContext = getContext();
        mTestContext = new TestContext(realContext);
        setupProviderAndResolver();
        assert isDatabaseEmpty(); // ensure we're not messing with real data

        mTestContext.setResolver(mResolver);
        setContext(mTestContext);
        setupService();
        mSystemFacade = new FakeSystemFacade();
        getService().mSystemFacade = mSystemFacade;

        mServer = new MockWebServer();
        mServer.play();
    }

    private void setupProviderAndResolver() {
        ContentProvider provider = new DownloadProvider();
        provider.attachInfo(mTestContext, null);
        mResolver = new MockContentResolver();
        mResolver.addProvider(PROVIDER_AUTHORITY, provider);
    }

    private boolean isDatabaseEmpty() {
        Cursor cursor = mResolver.query(Downloads.CONTENT_URI, null, null, null, null);
        try {
            return cursor.getCount() == 0;
        } finally {
            cursor.close();
        }
    }

    @Override
    protected void tearDown() throws Exception {
        cleanUpDownloads();
        super.tearDown();
    }

    /**
     * Remove any downloaded files and delete any lingering downloads.
     */
    private void cleanUpDownloads() {
        if (mResolver == null) {
            return;
        }
        String[] columns = new String[] {Downloads._DATA};
        Cursor cursor = mResolver.query(Downloads.CONTENT_URI, columns, null, null, null);
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                String filePath = cursor.getString(0);
                if (filePath == null) continue;
                Log.d(LOG_TAG, "Deleting " + filePath);
                new File(filePath).delete();
            }
        } finally {
            cursor.close();
        }
        mResolver.delete(Downloads.CONTENT_URI, null, null);
    }

    public void testBasicRequest() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);

        String path = "/download_manager_test_path";
        Uri downloadUri = requestDownload(path);
        assertEquals(Downloads.STATUS_PENDING, getDownloadStatus(downloadUri));
        assertTrue(mTestContext.mHasServiceBeenStarted);

        RecordedRequest request = runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
        assertEquals("GET", request.getMethod());
        assertEquals(path, request.getPath());
        assertEquals(FILE_CONTENT, getDownloadContents(downloadUri));
        assertStartsWith(Environment.getExternalStorageDirectory().getPath(),
                         getDownloadFilename(downloadUri));
    }

    public void testDownloadToCache() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Uri downloadUri = requestDownload("/path");
        updateDownload(downloadUri, Downloads.COLUMN_DESTINATION,
                       Integer.toString(Downloads.DESTINATION_CACHE_PARTITION));
        runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
        assertEquals(FILE_CONTENT, getDownloadContents(downloadUri));
        assertStartsWith(Environment.getDownloadCacheDirectory().getPath(),
                         getDownloadFilename(downloadUri));
    }

    public void testFileNotFound() throws Exception {
        enqueueEmptyResponse(HTTP_NOT_FOUND);
        Uri downloadUri = requestDownload("/nonexistent_path");
        assertEquals(Downloads.STATUS_PENDING, getDownloadStatus(downloadUri));
        runUntilStatus(downloadUri, HTTP_NOT_FOUND);
    }

    public void testRetryAfter() throws Exception {
        final int delay = 120;
        enqueueEmptyResponse(HTTP_SERVICE_UNAVAILABLE).addHeader("Retry-after", delay);
        Uri downloadUri = requestDownload("/path");
        runUntilStatus(downloadUri, Downloads.STATUS_RUNNING_PAUSED);

        // download manager adds random 0-30s offset
        mSystemFacade.incrementTimeMillis((delay + 31) * 1000);

        enqueueResponse(HTTP_OK, FILE_CONTENT);
        runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
    }

    public void testRedirect() throws Exception {
        enqueueEmptyResponse(301).addHeader("Location", mServer.getUrl("/other_path").toString());
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Uri downloadUri = requestDownload("/path");
        RecordedRequest request = runUntilStatus(downloadUri, Downloads.STATUS_RUNNING_PAUSED);
        assertEquals("/path", request.getPath());

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        request = runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
        assertEquals("/other_path", request.getPath());
    }

    public void testBasicConnectivityChanges() throws Exception {
        enqueueResponse(HTTP_OK, FILE_CONTENT);
        Uri downloadUri = requestDownload("/path");

        // without connectivity, download immediately pauses
        mTestContext.mFakeIConnectivityManager.setNetworkState(NetworkInfo.State.DISCONNECTED);
        startService(null);
        waitForDownloadToStop(downloadUri, Downloads.STATUS_RUNNING_PAUSED);

        // connecting should start the download
        mTestContext.mFakeIConnectivityManager.setNetworkState(NetworkInfo.State.CONNECTED);
        runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);
    }

    public void testInterruptedDownload() throws Exception {
        int initialLength = 5;
        String etag = "my_etag";
        int totalLength = FILE_CONTENT.length();
        // the first response has normal headers but unexpectedly closes after initialLength bytes
        enqueueResponse(HTTP_OK, FILE_CONTENT.substring(0, initialLength))
                .addHeader("Content-length", totalLength)
                .addHeader("Etag", etag)
                .setCloseConnectionAfter(true);
        Uri downloadUri = requestDownload("/path");

        runUntilStatus(downloadUri, Downloads.STATUS_RUNNING_PAUSED);

        mSystemFacade.incrementTimeMillis(RETRY_DELAY_MILLIS);
        // the second response returns partial content for the rest of the data
        enqueueResponse(HTTP_PARTIAL_CONTENT, FILE_CONTENT.substring(initialLength))
                .addHeader("Content-range",
                           "bytes " + initialLength + "-" + totalLength + "/" + totalLength)
                .addHeader("Etag", etag);
        // TODO: ideally we wouldn't need to call startService again, but there's a bug where the
        // service won't retry a download until an intent comes in
        RecordedRequest request = runUntilStatus(downloadUri, Downloads.STATUS_SUCCESS);

        List<String> headers = request.getHeaders();
        assertTrue("No Range header: " + headers,
                   headers.contains("Range: bytes=" + initialLength + "-"));
        assertTrue("No ETag header: " + headers, headers.contains("If-Match: " + etag));
        assertEquals(FILE_CONTENT, getDownloadContents(downloadUri));
    }

    private void assertStartsWith(String expectedPrefix, String actual) {
        String regex = "^" + expectedPrefix + ".*";
        MoreAsserts.assertMatchesRegex(regex, actual);
    }

    /**
     * Enqueue a response from the MockWebServer.
     */
    private MockResponse enqueueResponse(int status, String body) {
        MockResponse response = new MockResponse()
                        .setResponseCode(status)
                        .setBody(body)
                        .addHeader("Content-type", "text/plain");
        mServer.enqueue(response);
        return response;
    }

    private MockResponse enqueueEmptyResponse(int status) {
        return enqueueResponse(status, "");
    }

    /**
     * Run the service and wait for a request and for the download to reach the given status.
     * @return the request received
     */
    private RecordedRequest runUntilStatus(Uri downloadUri, int status) throws Exception {
        startService(null);
        RecordedRequest request = takeRequest();
        waitForDownloadToStop(downloadUri, status);
        return request;
    }

    /**
     * Wait for a request to come to the MockWebServer and return it.
     */
    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = mServer.takeRequestWithTimeout(REQUEST_TIMEOUT_MILLIS);
        assertNotNull("Timed out waiting for request", request);
        return request;
    }

    /**
     * Read a downloaded file from disk.
     */
    private String getDownloadContents(Uri downloadUri) throws Exception {
        InputStream inputStream = mResolver.openInputStream(downloadUri);
        try {
            return readStream(inputStream);
        } finally {
            inputStream.close();
        }
    }

    /**
     * Wait for a download to given a given status, with a timeout.  Fails if the download reaches
     * any other final status.
     */
    private void waitForDownloadToStop(Uri downloadUri, int expectedStatus) throws Exception {
        // TODO(showard): find a better way to accomplish this
        long startTimeMillis = System.currentTimeMillis();
        int status = getDownloadStatus(downloadUri);
        while (status != expectedStatus) {
            if (!Downloads.isStatusInformational(status)) {
                fail("Download completed with unexpected status: " + status);
            }
            if (System.currentTimeMillis() > startTimeMillis + REQUEST_TIMEOUT_MILLIS) {
                fail("Download timed out with status " + status);
            }
            Thread.sleep(100);
            mServer.checkForExceptions();
            status = getDownloadStatus(downloadUri);
        }

        long delta = System.currentTimeMillis() - startTimeMillis;
        Log.d(LOG_TAG, "Status " + status + " reached after " + delta + "ms");
    }

    private int getDownloadStatus(Uri downloadUri) {
        return Integer.valueOf(getDownloadField(downloadUri, Downloads.COLUMN_STATUS));
    }

    private String getDownloadFilename(Uri downloadUri) {
        return getDownloadField(downloadUri, Downloads._DATA);
    }

    private String getDownloadField(Uri downloadUri, String column) {
        final String[] columns = new String[] {column};
        Cursor cursor = mResolver.query(downloadUri, columns, null, null, null);
        try {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            return cursor.getString(0);
        } finally {
            cursor.close();
        }
    }

    /**
     * Request a download from the Download Manager.
     */
    private Uri requestDownload(String path) throws MalformedURLException {
        ContentValues values = new ContentValues();
        values.put(Downloads.COLUMN_URI, mServer.getUrl(path).toString());
        values.put(Downloads.COLUMN_DESTINATION, Downloads.DESTINATION_EXTERNAL);
        return mResolver.insert(Downloads.CONTENT_URI, values);
    }

    /**
     * Update one field of a download in the provider.
     */
    private void updateDownload(Uri downloadUri, String column, String value) {
        ContentValues values = new ContentValues();
        values.put(column, value);
        int numChanged = mResolver.update(downloadUri, values, null, null);
        assertEquals(1, numChanged);
    }

    private String readStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        try {
            char[] buffer = new char[1024];
            int length = reader.read(buffer);
            assertTrue("Failed to read anything from input stream", length > -1);
            return String.valueOf(buffer, 0, length);
        } finally {
            reader.close();
        }
    }
}