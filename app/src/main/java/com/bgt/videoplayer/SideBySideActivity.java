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

package com.bgt.videoplayer;

import android.annotation.TargetApi;
import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.widget.MediaController;

import net.protyposis.android.mediaplayer.VideoView;

import java.util.ArrayList;
import java.util.List;


public class SideBySideActivity extends AppCompatActivity {

    private static final String TAG = SideBySideActivity.class.getSimpleName();

    private Uri mVideoUri;
    private android.widget.VideoView mAndroidVideoView;
    private VideoView mMpxVideoView;

    private MediaPlayerMultiControl mMediaPlayerControl;
    private MediaController mMediaController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_side_by_side);

        mAndroidVideoView = (android.widget.VideoView) findViewById(R.id.androidvv);
        mMpxVideoView = (VideoView) findViewById(R.id.mpxvv);

        mMediaPlayerControl = new MediaPlayerMultiControl(mAndroidVideoView, mMpxVideoView);
        mMediaController = new MediaController(this);
        mMediaController.setAnchorView(findViewById(R.id.container));
        mMediaController.setMediaPlayer(mMediaPlayerControl);
        mMediaController.setEnabled(false);

        mVideoUri = getIntent().getData();
        getSupportActionBar().setSubtitle(mVideoUri+"");

        // HACK: this needs to be deferred, else it fails when setting video on both players (it works when doing it just on one)
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                final Runnable enableMediaController = new Runnable() {
                    int preparedCount = 0;
                    @Override
                    public void run() {
                        if(++preparedCount == mMediaPlayerControl.getControlsCount()) {
                            // Enable controller when all players are initialized
                            mMediaController.setEnabled(true);
                        }
                    }
                };

                mAndroidVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        mAndroidVideoView.seekTo(0); // display first frame
                        enableMediaController.run();

                    }
                });
                mMpxVideoView.setOnPreparedListener(new net.protyposis.android.mediaplayer.MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(net.protyposis.android.mediaplayer.MediaPlayer mp) {
                        mMpxVideoView.seekTo(0); // display first frame
                        enableMediaController.run();
                    }
                });

                mAndroidVideoView.setVideoURI(mVideoUri);
                mMpxVideoView.setVideoURI(mVideoUri);
            }
        }, 1000);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.side_by_side, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mMediaController.show();
        return super.onTouchEvent(event);
    }

    @Override
    protected void onStop() {
        mMediaController.hide();
        super.onStop();
    }

    private class MediaPlayerMultiControl implements MediaController.MediaPlayerControl {

        private List<MediaController.MediaPlayerControl> mControls;

        public MediaPlayerMultiControl(MediaController.MediaPlayerControl... controls) {
            mControls = new ArrayList<>();
            for(MediaController.MediaPlayerControl mpc : controls) {
                mControls.add(mpc);
            }
        }

        public int getControlsCount() {
            return mControls.size();
        }

        @Override
        public void start() {
            for(MediaController.MediaPlayerControl mpc : mControls) {
                mpc.start();
            }
        }

        @Override
        public void pause() {
            for(MediaController.MediaPlayerControl mpc : mControls) {
                mpc.pause();
            }
        }

        @Override
        public int getDuration() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).getDuration();
            }
            return 0;
        }

        @Override
        public int getCurrentPosition() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).getCurrentPosition();
            }
            return 0;
        }

        @Override
        public void seekTo(int pos) {
            for(MediaController.MediaPlayerControl mpc : mControls) {
                mpc.seekTo(pos);
            }
        }

        @Override
        public boolean isPlaying() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).isPlaying();
            }
            return false;
        }

        @Override
        public int getBufferPercentage() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).getBufferPercentage();
            }
            return 0;
        }

        @Override
        public boolean canPause() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).canPause();
            }
            return false;
        }

        @Override
        public boolean canSeekBackward() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).canSeekBackward();
            }
            return false;
        }

        @Override
        public boolean canSeekForward() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).canSeekForward();
            }
            return false;
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public int getAudioSessionId() {
            if(!mControls.isEmpty()) {
                return mControls.get(0).getAudioSessionId();
            }
            return 0;
        }
    }
}
