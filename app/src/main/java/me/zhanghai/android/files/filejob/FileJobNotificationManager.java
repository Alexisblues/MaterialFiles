/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Service;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import me.zhanghai.android.files.notification.Notifications;

public class FileJobNotificationManager {

    public static final String CHANNEL_ID = Notifications.Channels.FILE_JOB.ID;

    private static final long NOTIFY_INTERVAL_MILLIS = 500;

    @NonNull
    private final Service mService;

    @NonNull
    private final NotificationManagerCompat mNotificationManager;

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final Map<Integer, Long> mNextNotifyTime = new HashMap<>();
    @NonNull
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message message) {
            notifyInternal(message.what, (Notification) message.obj);
        }
    };

    private boolean mChannelCreated;

    @NonNull
    private final Map<Integer, Notification> mNotifications = new HashMap<>();
    private int mForegroundId;

    public FileJobNotificationManager(@NonNull Service service) {
        mService = service;

        mNotificationManager = NotificationManagerCompat.from(mService);
    }

    private void ensureChannelLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        if (mChannelCreated) {
            return;
        }
        @SuppressLint("WrongConstant")
        NotificationChannel channel = new NotificationChannel(Notifications.Channels.FILE_JOB.ID,
                mService.getString(Notifications.Channels.FILE_JOB.NAME_RES),
                Notifications.Channels.FILE_JOB.IMPORTANCE);
        channel.setDescription(mService.getString(Notifications.Channels.FILE_JOB.DESCRIPTION_RES));
        channel.setShowBadge(false);
        mNotificationManager.createNotificationChannel(channel);
        mChannelCreated = true;
    }

    public void notify(int id, @NonNull Notification notification) {
        synchronized (mLock) {
            mHandler.removeMessages(id);
            Long time = mNextNotifyTime.get(id);
            long uptime = SystemClock.uptimeMillis();
            if (time == null) {
                // Add some delay for the first time, so that notification doesn't pop up and
                // disappear immediately.
                time = uptime + NOTIFY_INTERVAL_MILLIS;
            } else if (time < uptime) {
                time = Math.max(uptime, time + NOTIFY_INTERVAL_MILLIS);
            }
            mHandler.sendMessageAtTime(mHandler.obtainMessage(id, notification), time);
            mNextNotifyTime.put(id, time);
        }
    }

    private void notifyInternal(int id, @NonNull Notification notification) {
        synchronized (mLock) {
            ensureChannelLocked();
            if (mNotifications.isEmpty()) {
                mService.startForeground(id, notification);
                mNotifications.put(id, notification);
                mForegroundId = id;
            } else {
                if (id == mForegroundId) {
                    mService.startForeground(id, notification);
                } else {
                    mNotificationManager.notify(id, notification);
                }
                mNotifications.put(id, notification);
            }
        }
    }

    public void cancel(int id) {
        synchronized (mLock) {
            mHandler.removeMessages(id);
            mNextNotifyTime.remove(id);
            if (!mNotifications.containsKey(id)) {
                return;
            }
            if (id == mForegroundId) {
                if (mNotifications.size() == 1) {
                    mService.stopForeground(true);
                    mNotifications.remove(id);
                    mForegroundId = 0;
                } else {
                    for (Map.Entry<Integer, Notification> entry : mNotifications.entrySet()) {
                        int entryId = entry.getKey();
                        if (entryId == id) {
                            continue;
                        }
                        mService.startForeground(entryId, entry.getValue());
                        mForegroundId = entryId;
                        break;
                    }
                    mNotificationManager.cancel(id);
                    mNotifications.remove(id);
                }
            } else {
                mNotificationManager.cancel(id);
                mNotifications.remove(id);
            }
        }
    }
}
