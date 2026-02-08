package com.example.calldemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class CallForegroundService extends Service {
    private static final String CHANNEL_ID = "CallDemo_Channel";
    private static final int NOTIFICATION_ID = 1001; // 前台服务通知ID

    @Override
    public void onCreate() {
        super.onCreate();
        // 创建通知渠道（Android 8.0+ 必需）
        createNotificationChannel();
        // 启动前台服务（必须显示通知，无法隐藏）
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 无需绑定，返回null
    }

    // 创建通知渠道
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "音视频通话", // 通知名称（用户可见）
                    NotificationManager.IMPORTANCE_LOW // 低优先级，不弹出提示
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    // 构建前台服务通知（必须，否则前台服务启动失败）
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("音视频通话中")
                .setContentText("后台保持通话连接")
                .setSmallIcon(R.mipmap.ic_launcher) // 替换为你的应用图标
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // 不可手动关闭
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(STOP_FOREGROUND_REMOVE); // 停止前台服务，移除通知
    }
}