/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.support.v4.media.session;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.BundleCompat;
import android.support.v4.app.SupportActivity;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.VolumeProviderCompat;
import android.support.v4.media.session.MediaSessionCompat.QueueItem;
import android.support.v4.media.session.PlaybackStateCompat.CustomAction;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Allows an app to interact with an ongoing media session. Media buttons and
 * other commands can be sent to the session. A callback may be registered to
 * receive updates from the session, such as metadata and play state changes.
 * <p>
 * A MediaController can be created if you have a {@link MediaSessionCompat.Token}
 * from the session owner.
 * <p>
 * MediaController objects are thread-safe.
 * <p>
 * This is a helper for accessing features in {@link android.media.session.MediaSession}
 * introduced after API level 4 in a backwards compatible fashion.
 */
public final class MediaControllerCompat {
    static final String TAG = "MediaControllerCompat";

    static final String COMMAND_GET_EXTRA_BINDER =
            "android.support.v4.media.session.command.GET_EXTRA_BINDER";
    static final String COMMAND_ADD_QUEUE_ITEM =
            "android.support.v4.media.session.command.ADD_QUEUE_ITEM";
    static final String COMMAND_ADD_QUEUE_ITEM_AT =
            "android.support.v4.media.session.command.ADD_QUEUE_ITEM_AT";
    static final String COMMAND_REMOVE_QUEUE_ITEM =
            "android.support.v4.media.session.command.REMOVE_QUEUE_ITEM";
    static final String COMMAND_REMOVE_QUEUE_ITEM_AT =
            "android.support.v4.media.session.command.REMOVE_QUEUE_ITEM_AT";

    static final String COMMAND_ARGUMENT_MEDIA_DESCRIPTION =
            "android.support.v4.media.session.command.ARGUMENT_MEDIA_DESCRIPTION";
    static final String COMMAND_ARGUMENT_INDEX =
            "android.support.v4.media.session.command.ARGUMENT_INDEX";

    private static class MediaControllerExtraData extends SupportActivity.ExtraData {
        private final MediaControllerCompat mMediaController;

        MediaControllerExtraData(MediaControllerCompat mediaController) {
            mMediaController = mediaController;
        }

        MediaControllerCompat getMediaController() {
            return mMediaController;
        }
    }

    /**
     * Sets a {@link MediaControllerCompat} for later retrieval via
     * {@link #getMediaController()}.
     *
     * <p>On API 21 and later, this controller will be tied to the window of the activity and
     * media key and volume events which are received while the Activity is in the foreground
     * will be forwarded to the controller and used to invoke transport controls or adjust the
     * volume. Prior to API 21, the global handling of media key and volume events through an
     * active {@link android.support.v4.media.session.MediaSessionCompat} and media button receiver
     * will still be respected.</p>
     *
     * @param mediaController The controller for the session which should receive
     *     media keys and volume changes on API 21 and later.
     * @see #getMediaController()
     * @see Activity#setMediaController(android.media.session.MediaController)
     */
    public static void setMediaController(Activity activity,
            MediaControllerCompat mediaController) {
        if (activity instanceof  SupportActivity) {
            ((SupportActivity) activity).putExtraData(
                    new MediaControllerExtraData(mediaController));
        }
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            Object controllerObj = null;
            if (mediaController != null) {
                Object sessionTokenObj = mediaController.getSessionToken().getToken();
                controllerObj = MediaControllerCompatApi21.fromToken(activity, sessionTokenObj);
            }
            MediaControllerCompatApi21.setMediaController(activity, controllerObj);
        }
    }

    /**
     * Retrieves the current {@link MediaControllerCompat} for sending media key and volume events.
     *
     * @return The controller which should receive events.
     * @see #setMediaController(Activity,MediaControllerCompat)
     * @see #getMediaController()
     */
    public static MediaControllerCompat getMediaController(Activity activity) {
        if (activity instanceof SupportActivity) {
            MediaControllerExtraData extraData =
                    ((SupportActivity) activity).getExtraData(MediaControllerExtraData.class);
            return extraData != null ? extraData.getMediaController() : null;
        } else if (android.os.Build.VERSION.SDK_INT >= 21) {
            Object controllerObj = MediaControllerCompatApi21.getMediaController(activity);
            if (controllerObj == null) {
                return null;
            }
            Object sessionTokenObj = MediaControllerCompatApi21.getSessionToken(controllerObj);
            try {
                return new MediaControllerCompat(activity,
                        MediaSessionCompat.Token.fromToken(sessionTokenObj));
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getMediaController.", e);
            }
        }
        return null;
    }

    private final MediaControllerImpl mImpl;
    private final MediaSessionCompat.Token mToken;

    /**
     * Creates a media controller from a session.
     *
     * @param session The session to be controlled.
     */
    public MediaControllerCompat(Context context, MediaSessionCompat session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        mToken = session.getSessionToken();

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            mImpl = new MediaControllerImplApi24(context, session);
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            mImpl = new MediaControllerImplApi23(context, session);
        } else if (android.os.Build.VERSION.SDK_INT >= 21) {
            mImpl = new MediaControllerImplApi21(context, session);
        } else {
            mImpl = new MediaControllerImplBase(mToken);
        }
    }

    /**
     * Creates a media controller from a session token which may have
     * been obtained from another process.
     *
     * @param sessionToken The token of the session to be controlled.
     * @throws RemoteException if the session is not accessible.
     */
    public MediaControllerCompat(Context context, MediaSessionCompat.Token sessionToken)
            throws RemoteException {
        if (sessionToken == null) {
            throw new IllegalArgumentException("sessionToken must not be null");
        }
        mToken = sessionToken;

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            mImpl = new MediaControllerImplApi24(context, sessionToken);
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            mImpl = new MediaControllerImplApi23(context, sessionToken);
        } else if (android.os.Build.VERSION.SDK_INT >= 21) {
            mImpl = new MediaControllerImplApi21(context, sessionToken);
        } else {
            mImpl = new MediaControllerImplBase(mToken);
        }
    }

    /**
     * Get a {@link TransportControls} instance for this session.
     *
     * @return A controls instance
     */
    public TransportControls getTransportControls() {
        return mImpl.getTransportControls();
    }

    /**
     * Send the specified media button event to the session. Only media keys can
     * be sent by this method, other keys will be ignored.
     *
     * @param keyEvent The media button event to dispatch.
     * @return true if the event was sent to the session, false otherwise.
     */
    public boolean dispatchMediaButtonEvent(KeyEvent keyEvent) {
        if (keyEvent == null) {
            throw new IllegalArgumentException("KeyEvent may not be null");
        }
        return mImpl.dispatchMediaButtonEvent(keyEvent);
    }

    /**
     * Get the current playback state for this session.
     *
     * @return The current PlaybackState or null
     */
    public PlaybackStateCompat getPlaybackState() {
        return mImpl.getPlaybackState();
    }

    /**
     * Get the current metadata for this session.
     *
     * @return The current MediaMetadata or null.
     */
    public MediaMetadataCompat getMetadata() {
        return mImpl.getMetadata();
    }

    /**
     * Get the current play queue for this session if one is set. If you only
     * care about the current item {@link #getMetadata()} should be used.
     *
     * @return The current play queue or null.
     */
    public List<MediaSessionCompat.QueueItem> getQueue() {
        return mImpl.getQueue();
    }

    /**
     * Add a queue item from the given {@code description} at the end of the play queue
     * of this session. Not all sessions may support this.
     *
     * @param description The {@link MediaDescriptionCompat} for creating the
     *            {@link MediaSessionCompat.QueueItem} to be inserted.
     * @throws UnsupportedOperationException If this session doesn't support this.
     * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
     */
    public void addQueueItem(MediaDescriptionCompat description) {
        mImpl.addQueueItem(description);
    }

    /**
     * Add a queue item from the given {@code description} at the specified position
     * in the play queue of this session. Shifts the queue item currently at that position
     * (if any) and any subsequent queue items to the right (adds one to their indices).
     * Not all sessions may support this.
     *
     * @param description The {@link MediaDescriptionCompat} for creating the
     *            {@link MediaSessionCompat.QueueItem} to be inserted.
     * @param index The index at which the created {@link MediaSessionCompat.QueueItem}
     *            is to be inserted.
     * @throws UnsupportedOperationException If this session doesn't support this.
     * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
     */
    public void addQueueItem(MediaDescriptionCompat description, int index) {
        mImpl.addQueueItem(description, index);
    }

    /**
     * Remove the first occurrence of the specified {@link MediaSessionCompat.QueueItem}
     * with the given {@link MediaDescriptionCompat description} in the play queue of the
     * associated session. Not all sessions may support this.
     *
     * @param description The {@link MediaDescriptionCompat} for denoting the
     *            {@link MediaSessionCompat.QueueItem} to be removed.
     * @throws UnsupportedOperationException If this session doesn't support this.
     * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
     */
    public void removeQueueItem(MediaDescriptionCompat description) {
        mImpl.removeQueueItem(description);
    }

    /**
     * Remove an queue item at the specified position in the play queue
     * of this session. Not all sessions may support this.
     *
     * @param index The index of the element to be removed.
     * @throws UnsupportedOperationException If this session doesn't support this.
     * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
     */
    public void removeQueueItemAt(int index) {
        mImpl.removeQueueItemAt(index);
    }

    /**
     * Get the queue title for this session.
     */
    public CharSequence getQueueTitle() {
        return mImpl.getQueueTitle();
    }

    /**
     * Get the extras for this session.
     */
    public Bundle getExtras() {
        return mImpl.getExtras();
    }

    /**
     * Get the rating type supported by the session. One of:
     * <ul>
     * <li>{@link RatingCompat#RATING_NONE}</li>
     * <li>{@link RatingCompat#RATING_HEART}</li>
     * <li>{@link RatingCompat#RATING_THUMB_UP_DOWN}</li>
     * <li>{@link RatingCompat#RATING_3_STARS}</li>
     * <li>{@link RatingCompat#RATING_4_STARS}</li>
     * <li>{@link RatingCompat#RATING_5_STARS}</li>
     * <li>{@link RatingCompat#RATING_PERCENTAGE}</li>
     * </ul>
     *
     * @return The supported rating type
     */
    public int getRatingType() {
        return mImpl.getRatingType();
    }

    /**
     * Get the repeat mode for this session.
     *
     * @return The latest repeat mode set to the session, or
     *         {@link PlaybackStateCompat#REPEAT_MODE_NONE} if not set.
     */
    public int getRepeatMode() {
        return mImpl.getRepeatMode();
    }

    /**
     * Return whether the shuffle mode is enabled for this session.
     *
     * @return {@code true} if the shuffle mode is enabled, {@code false} if disabled or not set.
     */
    public boolean isShuffleModeEnabled() {
        return mImpl.isShuffleModeEnabled();
    }

    /**
     * Get the flags for this session. Flags are defined in
     * {@link MediaSessionCompat}.
     *
     * @return The current set of flags for the session.
     */
    public long getFlags() {
        return mImpl.getFlags();
    }

    /**
     * Get the current playback info for this session.
     *
     * @return The current playback info or null.
     */
    public PlaybackInfo getPlaybackInfo() {
        return mImpl.getPlaybackInfo();
    }

    /**
     * Get an intent for launching UI associated with this session if one
     * exists.
     *
     * @return A {@link PendingIntent} to launch UI or null.
     */
    public PendingIntent getSessionActivity() {
        return mImpl.getSessionActivity();
    }

    /**
     * Get the token for the session this controller is connected to.
     *
     * @return The session's token.
     */
    public MediaSessionCompat.Token getSessionToken() {
        return mToken;
    }

    /**
     * Set the volume of the output this session is playing on. The command will
     * be ignored if it does not support
     * {@link VolumeProviderCompat#VOLUME_CONTROL_ABSOLUTE}. The flags in
     * {@link AudioManager} may be used to affect the handling.
     *
     * @see #getPlaybackInfo()
     * @param value The value to set it to, between 0 and the reported max.
     * @param flags Flags from {@link AudioManager} to include with the volume
     *            request.
     */
    public void setVolumeTo(int value, int flags) {
        mImpl.setVolumeTo(value, flags);
    }

    /**
     * Adjust the volume of the output this session is playing on. The direction
     * must be one of {@link AudioManager#ADJUST_LOWER},
     * {@link AudioManager#ADJUST_RAISE}, or {@link AudioManager#ADJUST_SAME}.
     * The command will be ignored if the session does not support
     * {@link VolumeProviderCompat#VOLUME_CONTROL_RELATIVE} or
     * {@link VolumeProviderCompat#VOLUME_CONTROL_ABSOLUTE}. The flags in
     * {@link AudioManager} may be used to affect the handling.
     *
     * @see #getPlaybackInfo()
     * @param direction The direction to adjust the volume in.
     * @param flags Any flags to pass with the command.
     */
    public void adjustVolume(int direction, int flags) {
        mImpl.adjustVolume(direction, flags);
    }

    /**
     * Adds a callback to receive updates from the Session. Updates will be
     * posted on the caller's thread.
     *
     * @param callback The callback object, must not be null.
     */
    public void registerCallback(Callback callback) {
        registerCallback(callback, null);
    }

    /**
     * Adds a callback to receive updates from the session. Updates will be
     * posted on the specified handler's thread.
     *
     * @param callback The callback object, must not be null.
     * @param handler The handler to post updates on. If null the callers thread
     *            will be used.
     */
    public void registerCallback(Callback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (handler == null) {
            handler = new Handler();
        }
        mImpl.registerCallback(callback, handler);
    }

    /**
     * Stop receiving updates on the specified callback. If an update has
     * already been posted you may still receive it after calling this method.
     *
     * @param callback The callback to remove
     */
    public void unregisterCallback(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        mImpl.unregisterCallback(callback);
    }

    /**
     * Sends a generic command to the session. It is up to the session creator
     * to decide what commands and parameters they will support. As such,
     * commands should only be sent to sessions that the controller owns.
     *
     * @param command The command to send
     * @param params Any parameters to include with the command
     * @param cb The callback to receive the result on
     */
    public void sendCommand(String command, Bundle params, ResultReceiver cb) {
        if (TextUtils.isEmpty(command)) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }
        mImpl.sendCommand(command, params, cb);
    }

    /**
     * Get the session owner's package name.
     *
     * @return The package name of of the session owner.
     */
    public String getPackageName() {
        return mImpl.getPackageName();
    }

    @VisibleForTesting
    boolean isExtraBinderReady() {
        if (mImpl instanceof MediaControllerImplApi21) {
            return ((MediaControllerImplApi21) mImpl).mExtraBinder != null;
        } else {
            return false;
        }
    }

    /**
     * Gets the underlying framework
     * {@link android.media.session.MediaController} object.
     * <p>
     * This method is only supported on API 21+.
     * </p>
     *
     * @return The underlying {@link android.media.session.MediaController}
     *         object, or null if none.
     */
    public Object getMediaController() {
        return mImpl.getMediaController();
    }

    /**
     * Callback for receiving updates on from the session. A Callback can be
     * registered using {@link #registerCallback}
     */
    public static abstract class Callback implements IBinder.DeathRecipient {
        private final Object mCallbackObj;
        MessageHandler mHandler;
        boolean mHasExtraCallback;

        boolean mRegistered = false;

        public Callback() {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                mCallbackObj = MediaControllerCompatApi21.createCallback(new StubApi21());
            } else {
                mCallbackObj = new StubCompat();
            }
        }

        /**
         * Override to handle the session being destroyed. The session is no
         * longer valid after this call and calls to it will be ignored.
         */
        public void onSessionDestroyed() {
        }

        /**
         * Override to handle custom events sent by the session owner without a
         * specified interface. Controllers should only handle these for
         * sessions they own.
         *
         * @param event The event from the session.
         * @param extras Optional parameters for the event.
         */
        public void onSessionEvent(String event, Bundle extras) {
        }

        /**
         * Override to handle changes in playback state.
         *
         * @param state The new playback state of the session
         */
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
        }

        /**
         * Override to handle changes to the current metadata.
         *
         * @param metadata The current metadata for the session or null if none.
         * @see MediaMetadataCompat
         */
        public void onMetadataChanged(MediaMetadataCompat metadata) {
        }

        /**
         * Override to handle changes to items in the queue.
         *
         * @see MediaSessionCompat.QueueItem
         * @param queue A list of items in the current play queue. It should
         *            include the currently playing item as well as previous and
         *            upcoming items if applicable.
         */
        public void onQueueChanged(List<MediaSessionCompat.QueueItem> queue) {
        }

        /**
         * Override to handle changes to the queue title.
         *
         * @param title The title that should be displayed along with the play
         *            queue such as "Now Playing". May be null if there is no
         *            such title.
         */
        public void onQueueTitleChanged(CharSequence title) {
        }

        /**
         * Override to handle chagnes to the {@link MediaSessionCompat} extras.
         *
         * @param extras The extras that can include other information
         *            associated with the {@link MediaSessionCompat}.
         */
        public void onExtrasChanged(Bundle extras) {
        }

        /**
         * Override to handle changes to the audio info.
         *
         * @param info The current audio info for this session.
         */
        public void onAudioInfoChanged(PlaybackInfo info) {
        }

        /**
         * Override to handle changes to the repeat mode.
         *
         * @param repeatMode The repeat mode. It should be one of followings:
         *                   {@link PlaybackStateCompat#REPEAT_MODE_NONE},
         *                   {@link PlaybackStateCompat#REPEAT_MODE_ONE},
         *                   {@link PlaybackStateCompat#REPEAT_MODE_ALL}
         */
        public void onRepeatModeChanged(@PlaybackStateCompat.RepeatMode int repeatMode) {
        }

        /**
         * Override to handle changes to the shuffle mode.
         *
         * @param enabled {@code true} if the shuffle mode is enabled, {@code false} otherwise.
         */
        public void onShuffleModeChanged(boolean enabled) {
        }

        @Override
        public void binderDied() {
            onSessionDestroyed();
        }

        /**
         * Set the handler to use for pre 21 callbacks.
         */
        private void setHandler(Handler handler) {
            mHandler = new MessageHandler(handler.getLooper());
        }

        private class StubApi21 implements MediaControllerCompatApi21.Callback {
            StubApi21() {
            }

            @Override
            public void onSessionDestroyed() {
                Callback.this.onSessionDestroyed();
            }

            @Override
            public void onSessionEvent(String event, Bundle extras) {
                if (mHasExtraCallback && android.os.Build.VERSION.SDK_INT < 23) {
                    // Ignore. ExtraCallback will handle this.
                } else {
                    Callback.this.onSessionEvent(event, extras);
                }
            }

            @Override
            public void onPlaybackStateChanged(Object stateObj) {
                if (mHasExtraCallback) {
                    // Ignore. ExtraCallback will handle this.
                } else {
                    Callback.this.onPlaybackStateChanged(
                            PlaybackStateCompat.fromPlaybackState(stateObj));
                }
            }

            @Override
            public void onMetadataChanged(Object metadataObj) {
                Callback.this.onMetadataChanged(MediaMetadataCompat.fromMediaMetadata(metadataObj));
            }

            @Override
            public void onQueueChanged(List<?> queue) {
                Callback.this.onQueueChanged(QueueItem.fromQueueItemList(queue));
            }

            @Override
            public void onQueueTitleChanged(CharSequence title) {
                Callback.this.onQueueTitleChanged(title);
            }

            @Override
            public void onExtrasChanged(Bundle extras) {
                Callback.this.onExtrasChanged(extras);
            }

            @Override
            public void onAudioInfoChanged(
                    int type, int stream, int control, int max, int current) {
                Callback.this.onAudioInfoChanged(
                        new PlaybackInfo(type, stream, control, max, current));
            }
        }

        private class StubCompat extends IMediaControllerCallback.Stub {

            StubCompat() {
            }

            @Override
            public void onEvent(String event, Bundle extras) throws RemoteException {
                mHandler.post(MessageHandler.MSG_EVENT, event, extras);
            }

            @Override
            public void onSessionDestroyed() throws RemoteException {
                mHandler.post(MessageHandler.MSG_DESTROYED, null, null);
            }

            @Override
            public void onPlaybackStateChanged(PlaybackStateCompat state) throws RemoteException {
                mHandler.post(MessageHandler.MSG_UPDATE_PLAYBACK_STATE, state, null);
            }

            @Override
            public void onMetadataChanged(MediaMetadataCompat metadata) throws RemoteException {
                mHandler.post(MessageHandler.MSG_UPDATE_METADATA, metadata, null);
            }

            @Override
            public void onQueueChanged(List<QueueItem> queue) throws RemoteException {
                mHandler.post(MessageHandler.MSG_UPDATE_QUEUE, queue, null);
            }

            @Override
            public void onQueueTitleChanged(CharSequence title) throws RemoteException {
                mHandler.post(MessageHandler.MSG_UPDATE_QUEUE_TITLE, title, null);
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) throws RemoteException {
                mHandler.post(MessageHandler.MSG_UPDATE_REPEAT_MODE, repeatMode, null);
            }

            @Override
            public void onShuffleModeChanged(boolean enabled) throws RemoteException {
                mHandler.post(MessageHandler.MSG_UPDATE_SHUFFLE_MODE, enabled, null);
            }

            @Override
            public void onExtrasChanged(Bundle extras) throws RemoteException {
                mHandler.post(MessageHandler.MSG_UPDATE_EXTRAS, extras, null);
            }

            @Override
            public void onVolumeInfoChanged(ParcelableVolumeInfo info) throws RemoteException {
                PlaybackInfo pi = null;
                if (info != null) {
                    pi = new PlaybackInfo(info.volumeType, info.audioStream, info.controlType,
                            info.maxVolume, info.currentVolume);
                }
                mHandler.post(MessageHandler.MSG_UPDATE_VOLUME, pi, null);
            }
        }

        private class MessageHandler extends Handler {
            private static final int MSG_EVENT = 1;
            private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
            private static final int MSG_UPDATE_METADATA = 3;
            private static final int MSG_UPDATE_VOLUME = 4;
            private static final int MSG_UPDATE_QUEUE = 5;
            private static final int MSG_UPDATE_QUEUE_TITLE = 6;
            private static final int MSG_UPDATE_EXTRAS = 7;
            private static final int MSG_DESTROYED = 8;
            private static final int MSG_UPDATE_REPEAT_MODE = 9;
            private static final int MSG_UPDATE_SHUFFLE_MODE = 10;

            public MessageHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                if (!mRegistered) {
                    return;
                }
                switch (msg.what) {
                    case MSG_EVENT:
                        onSessionEvent((String) msg.obj, msg.getData());
                        break;
                    case MSG_UPDATE_PLAYBACK_STATE:
                        onPlaybackStateChanged((PlaybackStateCompat) msg.obj);
                        break;
                    case MSG_UPDATE_METADATA:
                        onMetadataChanged((MediaMetadataCompat) msg.obj);
                        break;
                    case MSG_UPDATE_QUEUE:
                        onQueueChanged((List<MediaSessionCompat.QueueItem>) msg.obj);
                        break;
                    case MSG_UPDATE_QUEUE_TITLE:
                        onQueueTitleChanged((CharSequence) msg.obj);
                        break;
                    case MSG_UPDATE_REPEAT_MODE:
                        onRepeatModeChanged((int) msg.obj);
                        break;
                    case MSG_UPDATE_SHUFFLE_MODE:
                        onShuffleModeChanged((boolean) msg.obj);
                        break;
                    case MSG_UPDATE_EXTRAS:
                        onExtrasChanged((Bundle) msg.obj);
                        break;
                    case MSG_UPDATE_VOLUME:
                        onAudioInfoChanged((PlaybackInfo) msg.obj);
                        break;
                    case MSG_DESTROYED:
                        onSessionDestroyed();
                        break;
                }
            }

            public void post(int what, Object obj, Bundle data) {
                Message msg = obtainMessage(what, obj);
                msg.setData(data);
                msg.sendToTarget();
            }
        }
    }

    /**
     * Interface for controlling media playback on a session. This allows an app
     * to send media transport commands to the session.
     */
    public static abstract class TransportControls {
        TransportControls() {
        }

        /**
         * Request that the player prepare its playback without audio focus. In other words, other
         * session can continue to play during the preparation of this session. This method can be
         * used to speed up the start of the playback. Once the preparation is done, the session
         * will change its playback state to {@link PlaybackStateCompat#STATE_PAUSED}. Afterwards,
         * {@link #play} can be called to start playback. If the preparation is not needed,
         * {@link #play} can be directly called without this method.
         */
        public abstract void prepare();

        /**
         * Request that the player prepare playback for a specific media id. In other words, other
         * session can continue to play during the preparation of this session. This method can be
         * used to speed up the start of the playback. Once the preparation is
         * done, the session will change its playback state to
         * {@link PlaybackStateCompat#STATE_PAUSED}. Afterwards, {@link #play} can be called to
         * start playback. If the preparation is not needed, {@link #playFromMediaId} can
         * be directly called without this method.
         *
         * @param mediaId The id of the requested media.
         * @param extras Optional extras that can include extra information about the media item
         *               to be prepared.
         */
        public abstract void prepareFromMediaId(String mediaId, Bundle extras);

        /**
         * Request that the player prepare playback for a specific search query.
         * An empty or null query should be treated as a request to prepare any
         * music. In other words, other session can continue to play during
         * the preparation of this session. This method can be used to speed up the start of the
         * playback. Once the preparation is done, the session will change its playback state to
         * {@link PlaybackStateCompat#STATE_PAUSED}. Afterwards, {@link #play} can be called to
         * start playback. If the preparation is not needed, {@link #playFromSearch} can be directly
         * called without this method.
         *
         * @param query The search query.
         * @param extras Optional extras that can include extra information
         *               about the query.
         */
        public abstract void prepareFromSearch(String query, Bundle extras);

        /**
         * Request that the player prepare playback for a specific {@link Uri}.
         * In other words, other session can continue to play during the preparation of this
         * session. This method can be used to speed up the start of the playback.
         * Once the preparation is done, the session will change its playback state to
         * {@link PlaybackStateCompat#STATE_PAUSED}. Afterwards, {@link #play} can be called to
         * start playback. If the preparation is not needed, {@link #playFromUri} can be directly
         * called without this method.
         *
         * @param uri The URI of the requested media.
         * @param extras Optional extras that can include extra information about the media item
         *               to be prepared.
         */
        public abstract void prepareFromUri(Uri uri, Bundle extras);

        /**
         * Request that the player start its playback at its current position.
         */
        public abstract void play();

        /**
         * Request that the player start playback for a specific {@link Uri}.
         *
         * @param mediaId The uri of the requested media.
         * @param extras Optional extras that can include extra information
         *            about the media item to be played.
         */
        public abstract void playFromMediaId(String mediaId, Bundle extras);

        /**
         * Request that the player start playback for a specific search query.
         * An empty or null query should be treated as a request to play any
         * music.
         *
         * @param query The search query.
         * @param extras Optional extras that can include extra information
         *            about the query.
         */
        public abstract void playFromSearch(String query, Bundle extras);

        /**
         * Request that the player start playback for a specific {@link Uri}.
         *
         * @param uri  The URI of the requested media.
         * @param extras Optional extras that can include extra information about the media item
         *               to be played.
         */
        public abstract void playFromUri(Uri uri, Bundle extras);

        /**
         * Play an item with a specific id in the play queue. If you specify an
         * id that is not in the play queue, the behavior is undefined.
         */
        public abstract void skipToQueueItem(long id);

        /**
         * Request that the player pause its playback and stay at its current
         * position.
         */
        public abstract void pause();

        /**
         * Request that the player stop its playback; it may clear its state in
         * whatever way is appropriate.
         */
        public abstract void stop();

        /**
         * Move to a new location in the media stream.
         *
         * @param pos Position to move to, in milliseconds.
         */
        public abstract void seekTo(long pos);

        /**
         * Start fast forwarding. If playback is already fast forwarding this
         * may increase the rate.
         */
        public abstract void fastForward();

        /**
         * Skip to the next item.
         */
        public abstract void skipToNext();

        /**
         * Start rewinding. If playback is already rewinding this may increase
         * the rate.
         */
        public abstract void rewind();

        /**
         * Skip to the previous item.
         */
        public abstract void skipToPrevious();

        /**
         * Rate the current content. This will cause the rating to be set for
         * the current user. The Rating type must match the type returned by
         * {@link #getRatingType()}.
         *
         * @param rating The rating to set for the current content
         */
        public abstract void setRating(RatingCompat rating);

        /**
         * Set the repeat mode for this session.
         *
         * @param repeatMode The repeat mode. Must be one of the followings:
         *                   {@link PlaybackStateCompat#REPEAT_MODE_NONE},
         *                   {@link PlaybackStateCompat#REPEAT_MODE_ONE},
         *                   {@link PlaybackStateCompat#REPEAT_MODE_ALL}
         */
        public abstract void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode);

        /**
         * Set the shuffle mode for this session.
         *
         * @param enabled {@code true} to enable the shuffle mode, {@code false} to disable.
         */
        public abstract void setShuffleModeEnabled(boolean enabled);

        /**
         * Send a custom action for the {@link MediaSessionCompat} to perform.
         *
         * @param customAction The action to perform.
         * @param args Optional arguments to supply to the
         *            {@link MediaSessionCompat} for this custom action.
         */
        public abstract void sendCustomAction(PlaybackStateCompat.CustomAction customAction,
                Bundle args);

        /**
         * Send the id and args from a custom action for the
         * {@link MediaSessionCompat} to perform.
         *
         * @see #sendCustomAction(PlaybackStateCompat.CustomAction action,
         *      Bundle args)
         * @param action The action identifier of the
         *            {@link PlaybackStateCompat.CustomAction} as specified by
         *            the {@link MediaSessionCompat}.
         * @param args Optional arguments to supply to the
         *            {@link MediaSessionCompat} for this custom action.
         */
        public abstract void sendCustomAction(String action, Bundle args);
    }

    /**
     * Holds information about the way volume is handled for this session.
     */
    public static final class PlaybackInfo {
        /**
         * The session uses local playback.
         */
        public static final int PLAYBACK_TYPE_LOCAL = 1;
        /**
         * The session uses remote playback.
         */
        public static final int PLAYBACK_TYPE_REMOTE = 2;

        private final int mPlaybackType;
        // TODO update audio stream with AudioAttributes support version
        private final int mAudioStream;
        private final int mVolumeControl;
        private final int mMaxVolume;
        private final int mCurrentVolume;

        PlaybackInfo(int type, int stream, int control, int max, int current) {
            mPlaybackType = type;
            mAudioStream = stream;
            mVolumeControl = control;
            mMaxVolume = max;
            mCurrentVolume = current;
        }

        /**
         * Get the type of volume handling, either local or remote. One of:
         * <ul>
         * <li>{@link PlaybackInfo#PLAYBACK_TYPE_LOCAL}</li>
         * <li>{@link PlaybackInfo#PLAYBACK_TYPE_REMOTE}</li>
         * </ul>
         *
         * @return The type of volume handling this session is using.
         */
        public int getPlaybackType() {
            return mPlaybackType;
        }

        /**
         * Get the stream this is currently controlling volume on. When the volume
         * type is {@link PlaybackInfo#PLAYBACK_TYPE_REMOTE} this value does not
         * have meaning and should be ignored.
         *
         * @return The stream this session is playing on.
         */
        public int getAudioStream() {
            // TODO switch to AudioAttributesCompat when it is added.
            return mAudioStream;
        }

        /**
         * Get the type of volume control that can be used. One of:
         * <ul>
         * <li>{@link VolumeProviderCompat#VOLUME_CONTROL_ABSOLUTE}</li>
         * <li>{@link VolumeProviderCompat#VOLUME_CONTROL_RELATIVE}</li>
         * <li>{@link VolumeProviderCompat#VOLUME_CONTROL_FIXED}</li>
         * </ul>
         *
         * @return The type of volume control that may be used with this
         *         session.
         */
        public int getVolumeControl() {
            return mVolumeControl;
        }

        /**
         * Get the maximum volume that may be set for this session.
         *
         * @return The maximum allowed volume where this session is playing.
         */
        public int getMaxVolume() {
            return mMaxVolume;
        }

        /**
         * Get the current volume for this session.
         *
         * @return The current volume where this session is playing.
         */
        public int getCurrentVolume() {
            return mCurrentVolume;
        }
    }

    interface MediaControllerImpl {
        void registerCallback(Callback callback, Handler handler);

        void unregisterCallback(Callback callback);
        boolean dispatchMediaButtonEvent(KeyEvent keyEvent);
        TransportControls getTransportControls();
        PlaybackStateCompat getPlaybackState();
        MediaMetadataCompat getMetadata();

        List<MediaSessionCompat.QueueItem> getQueue();
        void addQueueItem(MediaDescriptionCompat description);
        void addQueueItem(MediaDescriptionCompat description, int index);
        void removeQueueItem(MediaDescriptionCompat description);
        void removeQueueItemAt(int index);
        CharSequence getQueueTitle();
        Bundle getExtras();
        int getRatingType();
        int getRepeatMode();
        boolean isShuffleModeEnabled();
        long getFlags();
        PlaybackInfo getPlaybackInfo();
        PendingIntent getSessionActivity();

        void setVolumeTo(int value, int flags);
        void adjustVolume(int direction, int flags);
        void sendCommand(String command, Bundle params, ResultReceiver cb);

        String getPackageName();
        Object getMediaController();
    }

    static class MediaControllerImplBase implements MediaControllerImpl {
        private MediaSessionCompat.Token mToken;
        private IMediaSession mBinder;
        private TransportControls mTransportControls;

        public MediaControllerImplBase(MediaSessionCompat.Token token) {
            mToken = token;
            mBinder = IMediaSession.Stub.asInterface((IBinder) token.getToken());
        }

        @Override
        public void registerCallback(Callback callback, Handler handler) {
            if (callback == null) {
                throw new IllegalArgumentException("callback may not be null.");
            }
            try {
                mBinder.asBinder().linkToDeath(callback, 0);
                mBinder.registerCallbackListener((IMediaControllerCallback) callback.mCallbackObj);
                callback.setHandler(handler);
                callback.mRegistered = true;
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in registerCallback.", e);
                callback.onSessionDestroyed();
            }
        }

        @Override
        public void unregisterCallback(Callback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback may not be null.");
            }
            try {
                mBinder.unregisterCallbackListener(
                        (IMediaControllerCallback) callback.mCallbackObj);
                mBinder.asBinder().unlinkToDeath(callback, 0);
                callback.mRegistered = false;
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in unregisterCallback.", e);
            }
        }

        @Override
        public boolean dispatchMediaButtonEvent(KeyEvent event) {
            if (event == null) {
                throw new IllegalArgumentException("event may not be null.");
            }
            try {
                mBinder.sendMediaButton(event);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in dispatchMediaButtonEvent.", e);
            }
            return false;
        }

        @Override
        public TransportControls getTransportControls() {
            if (mTransportControls == null) {
                mTransportControls = new TransportControlsBase(mBinder);
            }

            return mTransportControls;
        }

        @Override
        public PlaybackStateCompat getPlaybackState() {
            try {
                return mBinder.getPlaybackState();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getPlaybackState.", e);
            }
            return null;
        }

        @Override
        public MediaMetadataCompat getMetadata() {
            try {
                return mBinder.getMetadata();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getMetadata.", e);
            }
            return null;
        }

        @Override
        public List<MediaSessionCompat.QueueItem> getQueue() {
            try {
                return mBinder.getQueue();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getQueue.", e);
            }
            return null;
        }

        @Override
        public void addQueueItem(MediaDescriptionCompat description) {
            try {
                long flags = mBinder.getFlags();
                if ((flags & MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS) == 0) {
                    throw new UnsupportedOperationException(
                            "This session doesn't support queue management operations");
                }
                mBinder.addQueueItem(description);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in addQueueItem.", e);
            }
        }

        @Override
        public void addQueueItem(MediaDescriptionCompat description, int index) {
            try {
                long flags = mBinder.getFlags();
                if ((flags & MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS) == 0) {
                    throw new UnsupportedOperationException(
                            "This session doesn't support queue management operations");
                }
                mBinder.addQueueItemAt(description, index);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in addQueueItemAt.", e);
            }
        }

        @Override
        public void removeQueueItem(MediaDescriptionCompat description) {
            try {
                long flags = mBinder.getFlags();
                if ((flags & MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS) == 0) {
                    throw new UnsupportedOperationException(
                            "This session doesn't support queue management operations");
                }
                mBinder.removeQueueItem(description);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in removeQueueItem.", e);
            }
        }

        @Override
        public void removeQueueItemAt(int index) {
            try {
                long flags = mBinder.getFlags();
                if ((flags & MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS) == 0) {
                    throw new UnsupportedOperationException(
                            "This session doesn't support queue management operations");
                }
                mBinder.removeQueueItemAt(index);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in removeQueueItemAt.", e);
            }
        }

        @Override
        public CharSequence getQueueTitle() {
            try {
                return mBinder.getQueueTitle();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getQueueTitle.", e);
            }
            return null;
        }

        @Override
        public Bundle getExtras() {
            try {
                return mBinder.getExtras();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getExtras.", e);
            }
            return null;
        }

        @Override
        public int getRatingType() {
            try {
                return mBinder.getRatingType();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getRatingType.", e);
            }
            return 0;
        }

        @Override
        public int getRepeatMode() {
            try {
                return mBinder.getRepeatMode();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getRepeatMode.", e);
            }
            return 0;
        }

        @Override
        public boolean isShuffleModeEnabled() {
            try {
                return mBinder.isShuffleModeEnabled();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in isShuffleModeEnabled.", e);
            }
            return false;
        }

        @Override
        public long getFlags() {
            try {
                return mBinder.getFlags();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getFlags.", e);
            }
            return 0;
        }

        @Override
        public PlaybackInfo getPlaybackInfo() {
            try {
                ParcelableVolumeInfo info = mBinder.getVolumeAttributes();
                PlaybackInfo pi = new PlaybackInfo(info.volumeType, info.audioStream,
                        info.controlType, info.maxVolume, info.currentVolume);
                return pi;
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getPlaybackInfo.", e);
            }
            return null;
        }

        @Override
        public PendingIntent getSessionActivity() {
            try {
                return mBinder.getLaunchPendingIntent();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getSessionActivity.", e);
            }
            return null;
        }

        @Override
        public void setVolumeTo(int value, int flags) {
            try {
                mBinder.setVolumeTo(value, flags, null);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in setVolumeTo.", e);
            }
        }

        @Override
        public void adjustVolume(int direction, int flags) {
            try {
                mBinder.adjustVolume(direction, flags, null);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in adjustVolume.", e);
            }
        }

        @Override
        public void sendCommand(String command, Bundle params, ResultReceiver cb) {
            try {
                mBinder.sendCommand(command, params,
                        new MediaSessionCompat.ResultReceiverWrapper(cb));
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in sendCommand.", e);
            }
        }

        @Override
        public String getPackageName() {
            try {
                return mBinder.getPackageName();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in getPackageName.", e);
            }
            return null;
        }

        @Override
        public Object getMediaController() {
            return null;
        }
    }

    static class TransportControlsBase extends TransportControls {
        private IMediaSession mBinder;

        public TransportControlsBase(IMediaSession binder) {
            mBinder = binder;
        }

        @Override
        public void prepare() {
            try {
                mBinder.prepare();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in prepare.", e);
            }
        }

        @Override
        public void prepareFromMediaId(String mediaId, Bundle extras) {
            try {
                mBinder.prepareFromMediaId(mediaId, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in prepareFromMediaId.", e);
            }
        }

        @Override
        public void prepareFromSearch(String query, Bundle extras) {
            try {
                mBinder.prepareFromSearch(query, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in prepareFromSearch.", e);
            }
        }

        @Override
        public void prepareFromUri(Uri uri, Bundle extras) {
            try {
                mBinder.prepareFromUri(uri, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in prepareFromUri.", e);
            }
        }

        @Override
        public void play() {
            try {
                mBinder.play();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in play.", e);
            }
        }

        @Override
        public void playFromMediaId(String mediaId, Bundle extras) {
            try {
                mBinder.playFromMediaId(mediaId, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in playFromMediaId.", e);
            }
        }

        @Override
        public void playFromSearch(String query, Bundle extras) {
            try {
                mBinder.playFromSearch(query, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in playFromSearch.", e);
            }
        }

        @Override
        public void playFromUri(Uri uri, Bundle extras) {
            try {
                mBinder.playFromUri(uri, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in playFromUri.", e);
            }
        }

        @Override
        public void skipToQueueItem(long id) {
            try {
                mBinder.skipToQueueItem(id);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in skipToQueueItem.", e);
            }
        }

        @Override
        public void pause() {
            try {
                mBinder.pause();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in pause.", e);
            }
        }

        @Override
        public void stop() {
            try {
                mBinder.stop();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in stop.", e);
            }
        }

        @Override
        public void seekTo(long pos) {
            try {
                mBinder.seekTo(pos);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in seekTo.", e);
            }
        }

        @Override
        public void fastForward() {
            try {
                mBinder.fastForward();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in fastForward.", e);
            }
        }

        @Override
        public void skipToNext() {
            try {
                mBinder.next();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in skipToNext.", e);
            }
        }

        @Override
        public void rewind() {
            try {
                mBinder.rewind();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in rewind.", e);
            }
        }

        @Override
        public void skipToPrevious() {
            try {
                mBinder.previous();
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in skipToPrevious.", e);
            }
        }

        @Override
        public void setRating(RatingCompat rating) {
            try {
                mBinder.rate(rating);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in setRating.", e);
            }
        }

        @Override
        public void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode) {
            try {
                mBinder.setRepeatMode(repeatMode);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in setRepeatMode.", e);
            }
        }

        @Override
        public void setShuffleModeEnabled(boolean enabled) {
            try {
                mBinder.setShuffleModeEnabled(enabled);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in setShuffleModeEnabled.", e);
            }
        }

        @Override
        public void sendCustomAction(CustomAction customAction, Bundle args) {
            sendCustomAction(customAction.getAction(), args);
        }

        @Override
        public void sendCustomAction(String action, Bundle args) {
            try {
                mBinder.sendCustomAction(action, args);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in sendCustomAction.", e);
            }
        }
    }

    static class MediaControllerImplApi21 implements MediaControllerImpl {
        protected final Object mControllerObj;

        // Extra binder is used for applying the framework change of new APIs and bug fixes
        // after API 21.
        private IMediaSession mExtraBinder;
        private HashMap<Callback, ExtraCallback> mCallbackMap = new HashMap<>();
        private List<Callback> mPendingCallbacks = new ArrayList<>();

        public MediaControllerImplApi21(Context context, MediaSessionCompat session) {
            mControllerObj = MediaControllerCompatApi21.fromToken(context,
                    session.getSessionToken().getToken());
            requestExtraBinder();
        }

        public MediaControllerImplApi21(Context context, MediaSessionCompat.Token sessionToken)
                throws RemoteException {
            mControllerObj = MediaControllerCompatApi21.fromToken(context,
                    sessionToken.getToken());
            if (mControllerObj == null) throw new RemoteException();
            requestExtraBinder();
        }

        @Override
        public final void registerCallback(Callback callback, Handler handler) {
            MediaControllerCompatApi21.registerCallback(
                    mControllerObj, callback.mCallbackObj, handler);
            if (mExtraBinder != null) {
                callback.setHandler(handler);
                ExtraCallback extraCallback = new ExtraCallback(callback);
                mCallbackMap.put(callback, extraCallback);
                callback.mHasExtraCallback = true;
                try {
                    mExtraBinder.registerCallbackListener(extraCallback);
                } catch (RemoteException e) {
                    Log.e(TAG, "Dead object in registerCallback.", e);
                }
            } else {
                callback.setHandler(handler);
                synchronized (mPendingCallbacks) {
                    mPendingCallbacks.add(callback);
                }
            }
        }

        @Override
        public final void unregisterCallback(Callback callback) {
            MediaControllerCompatApi21.unregisterCallback(mControllerObj, callback.mCallbackObj);
            if (mExtraBinder != null) {
                try {
                    ExtraCallback extraCallback = mCallbackMap.remove(callback);
                    if (extraCallback != null) {
                        mExtraBinder.unregisterCallbackListener(extraCallback);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Dead object in unregisterCallback.", e);
                }
            } else {
                synchronized (mPendingCallbacks) {
                    mPendingCallbacks.remove(callback);
                }
            }
        }

        @Override
        public boolean dispatchMediaButtonEvent(KeyEvent event) {
            return MediaControllerCompatApi21.dispatchMediaButtonEvent(mControllerObj, event);
        }

        @Override
        public TransportControls getTransportControls() {
            Object controlsObj = MediaControllerCompatApi21.getTransportControls(mControllerObj);
            return controlsObj != null ? new TransportControlsApi21(controlsObj) : null;
        }

        @Override
        public PlaybackStateCompat getPlaybackState() {
            if (mExtraBinder != null) {
                try {
                    return mExtraBinder.getPlaybackState();
                } catch (RemoteException e) {
                    Log.e(TAG, "Dead object in getPlaybackState.", e);
                }
            }
            Object stateObj = MediaControllerCompatApi21.getPlaybackState(mControllerObj);
            return stateObj != null ? PlaybackStateCompat.fromPlaybackState(stateObj) : null;
        }

        @Override
        public MediaMetadataCompat getMetadata() {
            Object metadataObj = MediaControllerCompatApi21.getMetadata(mControllerObj);
            return metadataObj != null ? MediaMetadataCompat.fromMediaMetadata(metadataObj) : null;
        }

        @Override
        public List<MediaSessionCompat.QueueItem> getQueue() {
            List<Object> queueObjs = MediaControllerCompatApi21.getQueue(mControllerObj);
            return queueObjs != null ? MediaSessionCompat.QueueItem.fromQueueItemList(queueObjs)
                    : null;
        }

        @Override
        public void addQueueItem(MediaDescriptionCompat description) {
            Bundle params = new Bundle();
            params.putParcelable(COMMAND_ARGUMENT_MEDIA_DESCRIPTION, description);
            sendCommand(COMMAND_ADD_QUEUE_ITEM, params, null);
        }

        @Override
        public void addQueueItem(MediaDescriptionCompat description, int index) {
            Bundle params = new Bundle();
            params.putParcelable(COMMAND_ARGUMENT_MEDIA_DESCRIPTION, description);
            params.putInt(COMMAND_ARGUMENT_INDEX, index);
            sendCommand(COMMAND_ADD_QUEUE_ITEM_AT, params, null);
        }

        @Override
        public void removeQueueItem(MediaDescriptionCompat description) {
            Bundle params = new Bundle();
            params.putParcelable(COMMAND_ARGUMENT_MEDIA_DESCRIPTION, description);
            sendCommand(COMMAND_REMOVE_QUEUE_ITEM, params, null);
        }

        @Override
        public void removeQueueItemAt(int index) {
            Bundle params = new Bundle();
            params.putInt(COMMAND_ARGUMENT_INDEX, index);
            sendCommand(COMMAND_REMOVE_QUEUE_ITEM_AT, params, null);
        }

        @Override
        public CharSequence getQueueTitle() {
            return MediaControllerCompatApi21.getQueueTitle(mControllerObj);
        }

        @Override
        public Bundle getExtras() {
            return MediaControllerCompatApi21.getExtras(mControllerObj);
        }

        @Override
        public int getRatingType() {
            if (android.os.Build.VERSION.SDK_INT < 22 && mExtraBinder != null) {
                try {
                    return mExtraBinder.getRatingType();
                } catch (RemoteException e) {
                    Log.e(TAG, "Dead object in getRatingType.", e);
                }
            }
            return MediaControllerCompatApi21.getRatingType(mControllerObj);
        }

        @Override
        public int getRepeatMode() {
            if (mExtraBinder != null) {
                try {
                    return mExtraBinder.getRepeatMode();
                } catch (RemoteException e) {
                    Log.e(TAG, "Dead object in getRepeatMode.", e);
                }
            }
            return PlaybackStateCompat.REPEAT_MODE_NONE;
        }

        @Override
        public boolean isShuffleModeEnabled() {
            if (mExtraBinder != null) {
                try {
                    return mExtraBinder.isShuffleModeEnabled();
                } catch (RemoteException e) {
                    Log.e(TAG, "Dead object in isShuffleModeEnabled.", e);
                }
            }
            return false;
        }

        @Override
        public long getFlags() {
            return MediaControllerCompatApi21.getFlags(mControllerObj);
        }

        @Override
        public PlaybackInfo getPlaybackInfo() {
            Object volumeInfoObj = MediaControllerCompatApi21.getPlaybackInfo(mControllerObj);
            return volumeInfoObj != null ? new PlaybackInfo(
                    MediaControllerCompatApi21.PlaybackInfo.getPlaybackType(volumeInfoObj),
                    MediaControllerCompatApi21.PlaybackInfo.getLegacyAudioStream(volumeInfoObj),
                    MediaControllerCompatApi21.PlaybackInfo.getVolumeControl(volumeInfoObj),
                    MediaControllerCompatApi21.PlaybackInfo.getMaxVolume(volumeInfoObj),
                    MediaControllerCompatApi21.PlaybackInfo.getCurrentVolume(volumeInfoObj)) : null;
        }

        @Override
        public PendingIntent getSessionActivity() {
            return MediaControllerCompatApi21.getSessionActivity(mControllerObj);
        }

        @Override
        public void setVolumeTo(int value, int flags) {
            MediaControllerCompatApi21.setVolumeTo(mControllerObj, value, flags);
        }

        @Override
        public void adjustVolume(int direction, int flags) {
            MediaControllerCompatApi21.adjustVolume(mControllerObj, direction, flags);
        }

        @Override
        public void sendCommand(String command, Bundle params, ResultReceiver cb) {
            MediaControllerCompatApi21.sendCommand(mControllerObj, command, params, cb);
        }

        @Override
        public String getPackageName() {
            return MediaControllerCompatApi21.getPackageName(mControllerObj);
        }

        @Override
        public Object getMediaController() {
            return mControllerObj;
        }

        // TODO: Handle the case of calling other methods before receiving the extra binder.
        private void requestExtraBinder() {
            sendCommand(COMMAND_GET_EXTRA_BINDER, null,
                    new ExtraBinderRequestResultReceiver(this, new Handler()));
        }

        private void processPendingCallbacks() {
            if (mExtraBinder == null) {
                return;
            }
            synchronized (mPendingCallbacks) {
                for (Callback callback : mPendingCallbacks) {
                    ExtraCallback extraCallback = new ExtraCallback(callback);
                    mCallbackMap.put(callback, extraCallback);
                    callback.mHasExtraCallback = true;
                    try {
                        mExtraBinder.registerCallbackListener(extraCallback);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Dead object in registerCallback.", e);
                        break;
                    }
                }
                mPendingCallbacks.clear();
            }
        }

        private static class ExtraBinderRequestResultReceiver extends ResultReceiver {
            private WeakReference<MediaControllerImplApi21> mMediaControllerImpl;

            public ExtraBinderRequestResultReceiver(MediaControllerImplApi21 mediaControllerImpl,
                    Handler handler) {
                super(handler);
                mMediaControllerImpl = new WeakReference<>(mediaControllerImpl);
            }

            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                MediaControllerImplApi21 mediaControllerImpl = mMediaControllerImpl.get();
                if (mediaControllerImpl == null || resultData == null) {
                    return;
                }
                mediaControllerImpl.mExtraBinder = IMediaSession.Stub.asInterface(
                        BundleCompat.getBinder(resultData, MediaSessionCompat.EXTRA_BINDER));
                mediaControllerImpl.processPendingCallbacks();
            }
        }

        private class ExtraCallback extends IMediaControllerCallback.Stub {
            private Callback mCallback;

            ExtraCallback(Callback callback) {
                mCallback = callback;
            }

            @Override
            public void onEvent(final String event, final Bundle extras) throws RemoteException {
                mCallback.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCallback.onSessionEvent(event, extras);
                    }
                });
            }

            @Override
            public void onSessionDestroyed() throws RemoteException {
                // Will not be called.
                throw new AssertionError();
            }

            @Override
            public void onPlaybackStateChanged(final PlaybackStateCompat state)
                    throws RemoteException {
                mCallback.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCallback.onPlaybackStateChanged(state);
                    }
                });
            }

            @Override
            public void onMetadataChanged(MediaMetadataCompat metadata) throws RemoteException {
                // Will not be called.
                throw new AssertionError();
            }

            @Override
            public void onQueueChanged(List<QueueItem> queue) throws RemoteException {
                // Will not be called.
                throw new AssertionError();
            }

            @Override
            public void onQueueTitleChanged(CharSequence title) throws RemoteException {
                // Will not be called.
                throw new AssertionError();
            }

            @Override
            public void onRepeatModeChanged(final int repeatMode) throws RemoteException {
                mCallback.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCallback.onRepeatModeChanged(repeatMode);
                    }
                });
            }

            @Override
            public void onShuffleModeChanged(final boolean enabled) throws RemoteException {
                mCallback.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mCallback.onShuffleModeChanged(enabled);
                    }
                });
            }

            @Override
            public void onExtrasChanged(Bundle extras) throws RemoteException {
                // Will not be called.
                throw new AssertionError();
            }

            @Override
            public void onVolumeInfoChanged(ParcelableVolumeInfo info) throws RemoteException {
                // Will not be called.
                throw new AssertionError();
            }
        }
    }

    static class TransportControlsApi21 extends TransportControls {
        protected final Object mControlsObj;

        public TransportControlsApi21(Object controlsObj) {
            mControlsObj = controlsObj;
        }

        @Override
        public void prepare() {
            sendCustomAction(MediaSessionCompat.ACTION_PREPARE, null);
        }

        @Override
        public void prepareFromMediaId(String mediaId, Bundle extras) {
            Bundle bundle = new Bundle();
            bundle.putString(MediaSessionCompat.ACTION_ARGUMENT_MEDIA_ID, mediaId);
            bundle.putBundle(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
            sendCustomAction(MediaSessionCompat.ACTION_PREPARE_FROM_MEDIA_ID, bundle);
        }

        @Override
        public void prepareFromSearch(String query, Bundle extras) {
            Bundle bundle = new Bundle();
            bundle.putString(MediaSessionCompat.ACTION_ARGUMENT_QUERY, query);
            bundle.putBundle(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
            sendCustomAction(MediaSessionCompat.ACTION_PREPARE_FROM_SEARCH, bundle);
        }

        @Override
        public void prepareFromUri(Uri uri, Bundle extras) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(MediaSessionCompat.ACTION_ARGUMENT_URI, uri);
            bundle.putBundle(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
            sendCustomAction(MediaSessionCompat.ACTION_PREPARE_FROM_URI, bundle);
        }

        @Override
        public void play() {
            MediaControllerCompatApi21.TransportControls.play(mControlsObj);
        }

        @Override
        public void pause() {
            MediaControllerCompatApi21.TransportControls.pause(mControlsObj);
        }

        @Override
        public void stop() {
            MediaControllerCompatApi21.TransportControls.stop(mControlsObj);
        }

        @Override
        public void seekTo(long pos) {
            MediaControllerCompatApi21.TransportControls.seekTo(mControlsObj, pos);
        }

        @Override
        public void fastForward() {
            MediaControllerCompatApi21.TransportControls.fastForward(mControlsObj);
        }

        @Override
        public void rewind() {
            MediaControllerCompatApi21.TransportControls.rewind(mControlsObj);
        }

        @Override
        public void skipToNext() {
            MediaControllerCompatApi21.TransportControls.skipToNext(mControlsObj);
        }

        @Override
        public void skipToPrevious() {
            MediaControllerCompatApi21.TransportControls.skipToPrevious(mControlsObj);
        }

        @Override
        public void setRating(RatingCompat rating) {
            MediaControllerCompatApi21.TransportControls.setRating(mControlsObj,
                    rating != null ? rating.getRating() : null);
        }

        @Override
        public void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode) {
            Bundle bundle = new Bundle();
            bundle.putInt(MediaSessionCompat.ACTION_ARGUMENT_REPEAT_MODE, repeatMode);
            sendCustomAction(MediaSessionCompat.ACTION_SET_REPEAT_MODE, bundle);
        }

        @Override
        public void setShuffleModeEnabled(boolean enabled) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(MediaSessionCompat.ACTION_ARGUMENT_SHUFFLE_MODE_ENABLED, enabled);
            sendCustomAction(MediaSessionCompat.ACTION_SET_SHUFFLE_MODE_ENABLED, bundle);
        }

        @Override
        public void playFromMediaId(String mediaId, Bundle extras) {
            MediaControllerCompatApi21.TransportControls.playFromMediaId(mControlsObj, mediaId,
                    extras);
        }

        @Override
        public void playFromSearch(String query, Bundle extras) {
            MediaControllerCompatApi21.TransportControls.playFromSearch(mControlsObj, query,
                    extras);
        }

        @Override
        public void playFromUri(Uri uri, Bundle extras) {
            if (uri == null || Uri.EMPTY.equals(uri)) {
                throw new IllegalArgumentException(
                        "You must specify a non-empty Uri for playFromUri.");
            }
            Bundle bundle = new Bundle();
            bundle.putParcelable(MediaSessionCompat.ACTION_ARGUMENT_URI, uri);
            bundle.putParcelable(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
            sendCustomAction(MediaSessionCompat.ACTION_PLAY_FROM_URI, bundle);
        }

        @Override
        public void skipToQueueItem(long id) {
            MediaControllerCompatApi21.TransportControls.skipToQueueItem(mControlsObj, id);
        }

        @Override
        public void sendCustomAction(CustomAction customAction, Bundle args) {
            MediaControllerCompatApi21.TransportControls.sendCustomAction(mControlsObj,
                    customAction.getAction(), args);
        }

        @Override
        public void sendCustomAction(String action, Bundle args) {
            MediaControllerCompatApi21.TransportControls.sendCustomAction(mControlsObj, action,
                    args);
        }
    }

    static class MediaControllerImplApi23 extends MediaControllerImplApi21 {

        public MediaControllerImplApi23(Context context, MediaSessionCompat session) {
            super(context, session);
        }

        public MediaControllerImplApi23(Context context, MediaSessionCompat.Token sessionToken)
                throws RemoteException {
            super(context, sessionToken);
        }

        @Override
        public TransportControls getTransportControls() {
            Object controlsObj = MediaControllerCompatApi21.getTransportControls(mControllerObj);
            return controlsObj != null ? new TransportControlsApi23(controlsObj) : null;
        }
    }

    static class TransportControlsApi23 extends TransportControlsApi21 {

        public TransportControlsApi23(Object controlsObj) {
            super(controlsObj);
        }

        @Override
        public void playFromUri(Uri uri, Bundle extras) {
            MediaControllerCompatApi23.TransportControls.playFromUri(mControlsObj, uri,
                    extras);
        }
    }

    static class MediaControllerImplApi24 extends MediaControllerImplApi23 {

        public MediaControllerImplApi24(Context context, MediaSessionCompat session) {
            super(context, session);
        }

        public MediaControllerImplApi24(Context context, MediaSessionCompat.Token sessionToken)
                throws RemoteException {
            super(context, sessionToken);
        }

        @Override
        public TransportControls getTransportControls() {
            Object controlsObj = MediaControllerCompatApi21.getTransportControls(mControllerObj);
            return controlsObj != null ? new TransportControlsApi24(controlsObj) : null;
        }
    }

    static class TransportControlsApi24 extends TransportControlsApi23 {

        public TransportControlsApi24(Object controlsObj) {
            super(controlsObj);
        }

        @Override
        public void prepare() {
            MediaControllerCompatApi24.TransportControls.prepare(mControlsObj);
        }

        @Override
        public void prepareFromMediaId(String mediaId, Bundle extras) {
            MediaControllerCompatApi24.TransportControls.prepareFromMediaId(
                    mControlsObj, mediaId, extras);
        }

        @Override
        public void prepareFromSearch(String query, Bundle extras) {
            MediaControllerCompatApi24.TransportControls.prepareFromSearch(
                    mControlsObj, query, extras);
        }

        @Override
        public void prepareFromUri(Uri uri, Bundle extras) {
            MediaControllerCompatApi24.TransportControls.prepareFromUri(mControlsObj, uri, extras);
        }
    }
}
