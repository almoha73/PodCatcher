package com.almoha73.podcatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackService extends Service {

    public static final String ACTION_PLAY   = "com.almoha73.podcatcher.PLAY";
    public static final String ACTION_TOGGLE = "com.almoha73.podcatcher.TOGGLE";
    public static final String ACTION_REWIND = "com.almoha73.podcatcher.REWIND";
    public static final String ACTION_FFWD   = "com.almoha73.podcatcher.FFWD";
    public static final String ACTION_STOP   = "com.almoha73.podcatcher.STOP";

    public static final String EXTRA_URL     = "url";
    public static final String EXTRA_TITLE   = "title";
    public static final String EXTRA_AUTHOR  = "author";
    public static final String EXTRA_ARTWORK = "artwork";

    private static final String CHANNEL_ID      = "PodcatcherPlayback";
    private static final int    NOTIFICATION_ID = 201;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro XL) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    public interface StateListener {
        void onStateUpdate(boolean isPlaying, double currentSec, double durationSec, boolean ended);
    }

    private static PlaybackService instance;
    private static StateListener   listener;

    public static PlaybackService getInstance() { return instance; }
    public static void setListener(StateListener l) { listener = l; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();

    private ExoPlayer player;
    private MediaSession session;
    private NotificationManager notifManager;

    private String currentUrl = "";
    private String currentTitle = "Podcatcher";
    private String currentAuthor = "";
    private String currentArtworkUrl = "";
    private Bitmap currentArtworkBitmap = null;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            pushProgress(false);
            if (player != null && player.isPlaying()) {
                handler.postDelayed(this, 500);
            }
        }
    };

    public static void start(Context ctx, String url, String title, String author, String artwork) {
        Intent i = new Intent(ctx, PlaybackService.class);
        i.setAction(ACTION_PLAY);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_AUTHOR, author);
        i.putExtra(EXTRA_ARTWORK, artwork);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        createSession();
        createPlayer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PLAY.equals(action)) {
            startForegroundCompat();
            String url = intent.getStringExtra(EXTRA_URL);
            String title = intent.getStringExtra(EXTRA_TITLE);
            String author = intent.getStringExtra(EXTRA_AUTHOR);
            String artwork = intent.getStringExtra(EXTRA_ARTWORK);
            playStream(url, title, author, artwork);
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlay();
        } else if (ACTION_REWIND.equals(action)) {
            seekBy(-10);
        } else if (ACTION_FFWD.equals(action)) {
            seekBy(10);
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        }
        return START_NOT_STICKY;
    }

    private void createPlayer() {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000);

        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build();

        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                handler.removeCallbacks(progressRunnable);
                if (isPlaying) {
                    handler.post(progressRunnable);
                }
                updateSessionAndNotification();
                pushProgress(false);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                updateSessionAndNotification();
                if (playbackState == Player.STATE_ENDED) {
                    pushProgress(true);
                } else {
                    pushProgress(false);
                }
            }
        });
    }

    private void createSession() {
        session = new MediaSession(this, "PodcatcherSession");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (player != null) player.play();
            }

            @Override
            public void onPause() {
                if (player != null) player.pause();
            }

            @Override
            public void onRewind() {
                seekBy(-10);
            }

            @Override
            public void onFastForward() {
                seekBy(10);
            }

            @Override
            public void onSkipToPrevious() {
                seekBy(-10);
            }

            @Override
            public void onSkipToNext() {
                seekBy(10);
            }

            @Override
            public void onSeekTo(long pos) {
                if (player != null) {
                    player.seekTo(Math.max(0, pos));
                    updateSessionAndNotification();
                    pushProgress(false);
                }
            }

            @Override
            public void onStop() {
                stopPlayback();
            }
        });
        session.setActive(true);
    }

    public void playStream(String url, String title, String author, String artworkUrl) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        currentTitle = title != null ? title : "Podcast";
        currentAuthor = author != null ? author : "Podcatcher";
        if (artworkUrl != null && !artworkUrl.equals(currentArtworkUrl)) {
            currentArtworkUrl = artworkUrl;
            currentArtworkBitmap = null;
            fetchArtworkAsync(artworkUrl);
        }

        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        updateSessionAndNotification();
        pushProgress(false);
    }

    public void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            if (player.getPlaybackState() == Player.STATE_IDLE && !currentUrl.isEmpty()) {
                player.prepare();
            }
            player.play();
        }
        updateSessionAndNotification();
        pushProgress(false);
    }

    public void seekBy(int deltaSeconds) {
        if (player == null) return;
        long dur = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + deltaSeconds * 1000L);
        if (dur != C.TIME_UNSET && dur > 0) {
            target = Math.min(dur, target);
        }
        player.seekTo(target);
        updateSessionAndNotification();
        pushProgress(false);
    }

    public void seekToSeconds(double seconds) {
        if (player == null) return;
        long target = Math.max(0, Math.round(seconds * 1000.0));
        player.seekTo(target);
        updateSessionAndNotification();
        pushProgress(false);
    }

    public void stopPlayback() {
        handler.removeCallbacks(progressRunnable);
        if (player != null) {
            player.stop();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public void pushProgress(boolean ended) {
        if (listener == null || player == null) return;
        boolean playing = player.isPlaying();
        double cur = Math.max(0, player.getCurrentPosition()) / 1000.0;
        long durMs = player.getDuration();
        double dur = (durMs != C.TIME_UNSET && durMs > 0) ? (durMs / 1000.0) : 0.0;
        listener.onStateUpdate(playing, cur, dur, ended);
    }

    private void fetchArtworkAsync(final String urlStr) {
        artworkExecutor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                try (InputStream in = conn.getInputStream()) {
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        handler.post(() -> {
                            if (urlStr.equals(currentArtworkUrl)) {
                                currentArtworkBitmap = bmp;
                                updateSessionAndNotification();
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void updateSessionAndNotification() {
        if (session == null || player == null) return;

        long pos = Math.max(0, player.getCurrentPosition());
        long dur = player.getDuration();
        boolean playing = player.isPlaying();

        int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_REWIND
            | PlaybackState.ACTION_FAST_FORWARD
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_STOP;

        PlaybackState.Builder sb = new PlaybackState.Builder()
            .setActions(actions)
            .setState(state, pos, playing ? 1.0f : 0.0f);
        session.setPlaybackState(sb.build());

        MediaMetadata.Builder mb = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentAuthor);
        if (dur != C.TIME_UNSET && dur > 0) {
            mb.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        }
        if (currentArtworkBitmap != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArtworkBitmap);
        }
        session.setMetadata(mb.build());

        notifManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevPi = serviceActionPi(ACTION_REWIND, 1);
        PendingIntent togglePi = serviceActionPi(ACTION_TOGGLE, 2);
        PendingIntent nextPi = serviceActionPi(ACTION_FFWD, 3);

        boolean playing = player != null && player.isPlaying();
        int playPauseIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        Notification.Builder nb = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(currentTitle)
            .setContentText(currentAuthor)
            .setContentIntent(contentPi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_rew, "-10s", prevPi).build())
            .addAction(new Notification.Action.Builder(
                playPauseIcon, playing ? "Pause" : "Lecture", togglePi).build())
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_ff, "+10s", nextPi).build())
            .setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));

        if (currentArtworkBitmap != null) {
            nb.setLargeIcon(currentArtworkBitmap);
        }
        return nb.build();
    }

    private PendingIntent serviceActionPi(String action, int reqCode) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(
            this, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Lecture Podcast", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Contrôles de lecture Podcatcher");
            ch.setShowBadge(false);
            notifManager.createNotificationChannel(ch);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlayback();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(progressRunnable);
        artworkExecutor.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
        if (session != null) {
            session.release();
            session = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
