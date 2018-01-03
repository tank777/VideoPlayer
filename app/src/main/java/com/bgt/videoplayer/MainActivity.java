

package com.bgt.videoplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import net.protyposis.android.mediaplayer.MediaSource;
import net.protyposis.android.mediaplayer.dash.DashSource;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int REQUEST_LOAD_VIDEO = 1;

    private Button mVideoSelectButton;
    private Button mVideoSelect2Button;
    private Button mVideoViewButton;
    private Button mSideBySideButton;
    private Button mSideBySideSeekTestButton;

    private TextView mVideoUriText;
    private int mVideoUriTextColor;
    private Uri mVideoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mVideoSelectButton = (Button) findViewById(R.id.videoselect);
        mVideoSelect2Button = (Button) findViewById(R.id.videoselect2);
        mVideoViewButton = (Button) findViewById(R.id.videoview);
        mSideBySideButton = (Button) findViewById(R.id.sidebyside);
        mSideBySideSeekTestButton = (Button) findViewById(R.id.sidebysideseektest);
        mVideoUriText = (TextView) findViewById(R.id.videouri);
        mVideoUriTextColor = mVideoUriText.getCurrentTextColor();

        mVideoSelectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // open the picker...
                Log.d(TAG, "opening video picker...");
                Intent intent = null;

                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
                try {
                    startActivityForResult(intent, REQUEST_LOAD_VIDEO);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "Your device seems to lack a file picker", Toast.LENGTH_SHORT).show();
                }
            }
        });
       /* mVideoSelect2Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoURIInputDialogFragment dialog = new VideoURIInputDialogFragment();
                dialog.show(getFragmentManager(), null);
            }
        });
*/
        mVideoViewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, VideoViewActivity.class).setData(mVideoUri));
            }
        });
        mSideBySideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SideBySideActivity.class).setData(mVideoUri));
            }
        });
        /*mSideBySideSeekTestButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SideBySideSeekTestActivity.class).setData(mVideoUri));
            }
        });*/

        Uri uri = null;

        if (getIntent().getData() != null) {
            // The intent-filter probably caught an url, open it...
            uri = getIntent().getData();
        } else {
            String savedUriString = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                    .getString("lastUri", "");
            if (!"".equals(savedUriString)) {
                uri = Uri.parse(savedUriString);
            }
        }

        // internet streaming test files
        //uri = Uri.parse("http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4");
        //uri = Uri.parse("http://www-itec.uni-klu.ac.at/dash/js/content/bunny_4000.webm");

        // internet DASH streaming test files
        //uri = Uri.parse("http://www-itec.uni-klu.ac.at/dash/js/content/bigbuckbunny_1080p.mpd");
        //uri = Uri.parse("http://www-itec.uni-klu.ac.at/dash/js/content/bunny_ibmff_1080.mpd");
        //uri = Uri.parse("http://dj9wk94416cg5.cloudfront.net/sintel2/sintel.mpd");

        if (savedInstanceState != null) {
            uri = savedInstanceState.getParcelable("uri");
        }

        updateUri(uri);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_LOAD_VIDEO) {
            Log.d(TAG, "onActivityResult REQUEST_LOAD_VIDEO");

            if (resultCode == RESULT_OK) {
                updateUri(data.getData());
            } else {
                Log.w(TAG, "no file specified");
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putParcelable("uri", mVideoUri);
        super.onSaveInstanceState(outState);
    }

    private boolean updateUri(final Uri uri) {
        if (uri == null) {
            mVideoUriText.setText(getString(R.string.uri_missing));

            mVideoViewButton.setEnabled(false);
            mSideBySideButton.setEnabled(false);
            mSideBySideSeekTestButton.setEnabled(false);
        } else {
            updateUri(null); // disable buttons

            // Validate content URI
            try {
                if (uri.getScheme().equals("content")) {
                    ContentResolver cr = getContentResolver();
                    cr.openInputStream(uri).close();
                }
            } catch (Exception e) {
                // The content URI is invalid, probably because the file has been removed
                // or the system rebooted (which invalidates content URIs),
                // or the uri does not contain a scheme
                return false;
            }


            mVideoUriText.setText("Loading...");

            Utils.uriToMediaSourceAsync(MainActivity.this, uri, new Utils.MediaSourceAsyncCallbackHandler() {
                @Override
                public void onMediaSourceLoaded(MediaSource mediaSource) {
                    String text = uri.toString();
                    if (mediaSource instanceof DashSource) {
                        text = "DASH: " + text;
                    }
                    mVideoUriText.setText(text);
                    mVideoUriText.setTextColor(mVideoUriTextColor);
                    mVideoUri = uri;

                    mVideoViewButton.setEnabled(true);
                    mSideBySideButton.setEnabled(!(mediaSource instanceof DashSource));
                    mSideBySideSeekTestButton.setEnabled(true);

                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                            .edit().putString("lastUri", uri.toString()).commit();
                }

                @Override
                public void onException(Exception e) {
                    mVideoUriText.setText("Error loading video" + (e.getMessage() != null ? ": " + e.getMessage() : " :("));
                    mVideoUriText.setTextColor(Color.RED);
                    Log.e(TAG, "Error loading video", e);
                }
            });
        }

        return true;
    }
}
